# SPEC-CASE-02: Case lifecycle (create, read, list, transition, assign)
- **Requirements:** CASE-1, 2, 3 (metadata), 4 (manual assignment), 7, 8, 10 · ID-1..7 · ACC-3, ACC-11 · DATA hot window (ADR-003)
- **Status:** Implemented (agreed 2026-11-15, all example rows green)
- **Owner:** case-service
- **Depends on:** SPEC-CASE-01 (access scope), SPEC-IAM-01 (principals)

## 0. Decisions to agree
| # | Decision | Alternative (not chosen) |
|---|---|---|
| D1 | **Lists for country/territory staff fan out across the country's 64 shards in parallel** (virtual threads), take `limit+1` per shard by `case_id DESC` and merge. Growers and partners hit only the shards their growers live on | A search index or read model (OpenSearch, BRD phase 4): faster, but a new component |
| D2 | **The assignee is validated against IAM** via a new internal endpoint `GET /internal/users/by-id/{userId}/principal`: it must be an active STAFF user whose grants allow `case:read` on the case | Trusting the id, which risks assigning to someone who can't see the case |
| D3 | Country, territory and BU are **derived server-side**: country and territory from the grower (a snapshot at creation), BU from the product, or `buCode` when there is no product. Never taken from the client | — |
| D4 | Hot window = open, or closed less than 90 days ago. A list asking for older data (`createdFrom` < now − 90 days) answers **501 `ARCHIVE_NOT_ENABLED`** through the `ArchiveCaseQueryPort` placeholder until Snowflake (ADR-003) | — |
| D5 | Service layer and REST API are delivered together per spec. Increment 4 then adds the OpenAPI export, ArchUnit and the smoke script | Separate increments (the original plan) |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R20 | grower with `case:create` | `POST /cases` for **their own** grower record | 201. Initial status of the type; country and territory from the grower; BU from the product (or `buCode`); `sla_due_at = now + type SLA`; valid 15-digit `case_no` (country slot, shard digits, Damm check digit); `raised_by` = caller, `raised_channel` = APP. Activity `CREATED`, outbox `case.created`, `Location`, `ETag: "0"` |
| CASE-R21 | staff with `case:create` | creates for a grower inside their scope | 201, `raised_channel` = `CARE_CALL` (CENTRAL roles) or `FIELD` (FIELD_STAFF) |
| CASE-R22 | partner with `case:create` | creates for a grower of their country | 201, `raised_channel` = RETAILER, `raised_via_partner_org_id` = caller's org (so the partner can see it, SPEC-CASE-01 R2) |
| CASE-R23 | caller | names a grower they may not act for (another grower, another country, outside scope, unknown) | **404 `Grower`** |
| CASE-R24 | — | invalid input: no or invalid `Idempotency-Key`, unknown `caseType`, blank or too-long title (200) or description (4000), malformed ids, no product and no `buCode` | 400 `VALIDATION_FAILED` with field errors |
| CASE-R25 | — | product not REGISTERED in the case country · batch not of that product · farm not of that grower | 422 `case.product-not-registered` · `case.batch-unknown` · `case.farm-not-of-grower` |
| CASE-R26 | same `Idempotency-Key` | identical body is retried | **200** with the original case, header `Idempotent-Replayed: true`; no second row, activity or event |
| CASE-R27 | same `Idempotency-Key` | different body | 409 `IDEMPOTENCY_KEY_REUSED` |
| CASE-R28 | caller with `case:read` | `GET /cases/{caseId}` or `GET /cases/by-no/{caseNo}` for a case in scope | 200 + `ETag: "<version>"`; ids as strings; `caseNo` formatted `CC-DDDD-HH-SSSSSS-K` |
| CASE-R29 | caller | unknown id · out-of-scope case · malformed id · `case_no` with a wrong check digit · id of an unknown country | **404** (identical body for all) |
| CASE-R30 | grower PII (ACC-3) | case returned | `growerName`: full for FULL clearance (growers on their own case, field staff), masked `R***** P****` for MASKED (care, partners, managers). The phone is never returned, only `growerPhoneLast4` |
| CASE-R31 | caller with `case:read` | `GET /cases` | Only cases in scope (SPEC-CASE-01), in the hot window, newest first. Filters: `status`, `caseType`, `territoryId`, `assignedToMe`. `limit` 1–100 (default 20). Opaque `nextCursor`; pages never duplicate or skip rows |
| CASE-R32 | — | invalid `limit`, cursor, filter value | 400 |
| CASE-R33 | — | `createdFrom` older than 90 days | 501 `ARCHIVE_NOT_ENABLED` |
| CASE-R34 | staff with `case:transition` + the transition's `required_permission` | `POST /cases/{id}/transitions {toStatus, note}` with `If-Match: "<version>"` | 200, version + 1, new `ETag`. Entering a closed status sets `closed_at`; leaving one clears it. Activity `STATUS_CHANGED` (from, to, note), outbox `case.status_changed` |
| CASE-R35 | — | edge not in `ref.case_transition` for the case type | 422 `case.transition-not-allowed` |
| CASE-R36 | caller lacks `case:transition`, or the edge's `required_permission` (e.g. `compensation:approve`) | transition | 403 (if the case is in scope; out of scope stays 404) |
| CASE-R37 | — | `If-Match` missing · stale | 428 `PRECONDITION_REQUIRED` · 412 `STALE_VERSION` (with `expectedVersion`), no change |
| CASE-R38 | staff with `case:assign` | `PUT /cases/{id}/assignee {assigneeUserId}` + `If-Match` | 200, version + 1, activity `ASSIGNED`, outbox `case.assigned` |
| CASE-R39 | — | assignee unknown, not STAFF, or cannot `case:read` this case (D2) | 422 `case.assignee-not-eligible` |

## 2. Examples (each row = one parameterized test row)
**Fixtures:** dev seed.
- Grower 70001 (user 1, Pune 1002), grower 70002 (user 2, Nashik 1003), grower 70003 (BR), grower 70004 (no user, Karnataka 1005).
- Products: 9001 Azoxa (CP, registered IN + BR, batch AZX2604-117); 9003 hybrid corn (SEEDS, IN only).
- Farms: 80001 (of 70001), 80002 (of 70002).

| Example | Rule | Actor | Request | Expected |
|---|---|---|---|---|
| CASE-E20 | R20 | grower.in1 | PRODUCT_COMPLAINT, grower 70001, product 9001, batch AZX2604-117, farm 80001 | 201, status SUBMITTED, country IN, territory 1002, BU CP, channel APP, `case_no` starts `71`, Damm valid, ETag "0", 1 activity CREATED, 1 outbox `case.created` |
| CASE-E21 | R20 | grower.in1 | AGRONOMY_ADVICE, no product, `buCode` SEEDS | 201, BU SEEDS, status SUBMITTED, `sla_due_at` = now + 48h (±1 min) |
| CASE-E22 | R21 | care.in | PRODUCT_COMPLAINT for grower 70004 (assisted) | 201, channel CARE_CALL, `raised_by` 101 |
| CASE-E23 | R21 | tm.in | for grower 70002 (Nashik) | 201, channel FIELD |
| CASE-E24 | R22 | retailer.in | for grower 70001 | 201, channel RETAILER, partner 501, and retailer.in can then GET it |
| CASE-E25 | R23 | grower.in1 | for grower 70002 | 404 |
| CASE-E26 | R23 | care.in | for grower 70003 (BR) | 404 |
| CASE-E27 | R23 | tm.in | for grower 70004 (Karnataka) | 404 |
| CASE-E28 | R23 | grower.in1 | for grower 99999 | 404 |
| CASE-E29 | R24 | grower.in1 | no `Idempotency-Key` | 400 |
| CASE-E30 | R24 | grower.in1 | caseType `NOT_A_TYPE` / title "" / title 201 chars / description 4001 chars / no product + no buCode | 400 with the field |
| CASE-E31 | R25 | grower.br1 | product 9003 (not registered in BR) | 422 `case.product-not-registered` |
| CASE-E32 | R25 | grower.in1 | product 9001, batch `HYC2605-009` (batch of 9003) | 422 `case.batch-unknown` |
| CASE-E33 | R25 | grower.in1 | farm 80002 (of 70002) | 422 `case.farm-not-of-grower` |
| CASE-E34 | R26 | grower.in1 | same key + same body twice | 201 then 200 with the same caseId, `Idempotent-Replayed: true`, 1 row, 1 event |
| CASE-E35 | R27 | grower.in1 | same key, different title | 409 `IDEMPOTENCY_KEY_REUSED` |
| CASE-E36 | R28 | grower.in1 / care.in / tm.in | GET own E20 case by id and by number | 200, ETag "0" |
| CASE-E37 | R29 | grower.in2 / care.br / agro.in (not assigned) / retailer2.in | GET E20 case | 404 |
| CASE-E38 | R29 | care.in | GET `/cases/abc`, `/cases/0`, `/cases/by-no/71-0318-27-482139-5`, id with country index 5 | 404 |
| CASE-E39 | R30 | grower.in1 / tm.in → full; care.in / retailer.in → masked | GET E24 case | `growerName` "Ramesh Patil" vs "R***** P****"; `growerPhoneLast4` "3210"; no phone field |
| CASE-E40 | R31 | care.in | create 3 cases, then list `limit=2` and follow the cursor | pages [c3, c2], [c1, …]; no duplicates; only in-scope rows |
| CASE-E41 | R31 | grower.in2 | list | only grower 70002's cases |
| CASE-E42 | R31 | agro.in | list `assignedToMe=true` after E50 | exactly the assigned case |
| CASE-E43 | R32 | care.in | `limit=0`, `limit=101`, `cursor=%%%`, `status=lowercase` | 400 |
| CASE-E44 | R33 | care.in | `createdFrom` = now − 120 days | 501 |
| CASE-E45 | R34 | care.in | E20 case SUBMITTED → TRIAGED, `If-Match: "0"`, note "Checking batch" | 200, version 1, ETag "1", activity STATUS_CHANGED, outbox `case.status_changed` |
| CASE-E46 | R34 | care.in | an AGRONOMY_ADVICE case through IN_PROGRESS → ADVICE_GIVEN → CLOSED | `closedAt` set at CLOSED |
| CASE-E47 | R35 | care.in | SUBMITTED → CLOSED (PRODUCT_COMPLAINT) | 422 `case.transition-not-allowed` |
| CASE-E48 | R36 | grower.in1 | own case → TRIAGED | 403 |
| CASE-E49 | R36 | care.in | ROOT_CAUSE_DETERMINED → COMPENSATION_PROPOSED (needs `compensation:propose`) | 403 |
| CASE-E50 | R38 | tm.in | assign E20 case to user 102 (agro.in, Pune CP) | 200, version + 1, agro.in can now GET it |
| CASE-E51 | R39 | tm.in | assign to 105 (care.br: staff, but Brazil only) / to 1 (a grower) / to unknown 999 | 422 `case.assignee-not-eligible` |
| CASE-E52 | R37 | care.in | transition without `If-Match` · with `If-Match: "0"` after E45 | 428 · 412 |
| CASE-E53 | R36 | care.in | assign (care has `case:assign`) to 102 · grower.in1 assign | 200 · 403 |

## 3. API contract
| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/cases` | Header `Idempotency-Key: <uuid>`; `{growerId, caseType, title, description, farmId?, productId?, batchNo?, buCode?, priority?}` | 201 CaseView, `Location`, `ETag` · 200 on replay | 400, 401, 404, 409, 422 |
| GET | `/cases/{caseId}` · `/cases/by-no/{caseNo}` | — | 200 CaseView + `ETag` | 401, 404 |
| GET | `/cases?status&caseType&territoryId&assignedToMe&createdFrom&limit&cursor` | — | 200 `{items: CaseSummary[], nextCursor}` | 400, 401, 501 |
| POST | `/cases/{caseId}/transitions` | `If-Match`; `{toStatus, note?}` | 200 CaseView + `ETag` | 400, 401, 403, 404, 412, 422, 428 |
| PUT | `/cases/{caseId}/assignee` | `If-Match`; `{assigneeUserId}` | 200 CaseView + `ETag` | 400, 401, 403, 404, 412, 422, 428 |

**CaseView fields:**
- identity and type: `caseId`, `caseNo`, `caseType`, `status`, `priority`;
- where and what: `countryCode`, `territoryId`, `buCode`;
- people: `growerId`, `growerName` (masked per R30), `growerPhoneLast4`, `farmId`, `raisedByUserId`, `raisedChannel`, `raisedViaPartnerOrgId`, `assigneeUserId`;
- product: `productId`, `batchNo`;
- content: `title`, `description`;
- timing: `slaDueAt`, `createdAt`, `updatedAt`, `closedAt`, `version`.

`CaseSummary` = the same without `description`. All IDs are strings.

## 4. Data & events
- **Tables:** `<shard>.cases`, `case_activity`, `case_activity_detail`, `case_idempotency` (request hash = SHA-256 of the canonical JSON body), `outbox`.
- **Reads:** `ref.grower`, `farm`, `product`, `product_batch`, `product_registration`, `case_type`, `case_status`, `case_transition`.
- **Events** (`aggregateId` = caseId; payload IDs and codes only, no PII):
  - `case.created` {caseId, caseNo, caseType, status, countryCode, territoryId, buCode, growerId};
  - `case.status_changed` {caseId, from, to, version};
  - `case.assigned` {caseId, assigneeUserId, version}.
- **IAM addition (D2):** `GET /internal/users/by-id/{userId}/principal` (same contract as by-subject).

## 5. Security
| Endpoint | Function permission (403) | Object scope (404) |
|---|---|---|
| Create | `case:create` | Grower visibility per R23 |
| Read | `case:read` | `PolicyEnforcer` / `ScopeFilter` |
| Transition | `case:transition` + edge permission | `case:read` scope |
| Assign | `case:assign` | `case:read` scope |

The isolation matrix from SPEC-CASE-01 applies to every endpoint.

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R20–R22 / E20–E24 | `CaseLifecycleIT$Create#accepted` (parameterized: fields, case_no Damm/shard, SLA, ETag, activity, outbox, creator can read) |
| R23–R25 / E25–E33 | `CaseLifecycleIT$Create#rejected` (parameterized) |
| R26 / E34 · R27 / E35 | `CaseLifecycleIT$Create#caseE34…`, `#caseE35…`; hash rules in `CaseRulesTest#idempotencyHashDetectsChangedRequests` |
| R28/R29 / E36–E38 | `CaseLifecycleIT$Read#byIdAndByNumber`, `#malformedReferencesAreNotFound` (parameterized) |
| R30 / E39 | `CaseLifecycleIT$Read#growerNameIsMaskedByClearance`; `CaseRulesTest#growerNamesAreMaskedWordByWord` |
| R31–R33 / E40–E44 | `CaseLifecycleIT$Lists` (keyset pages, own-only, assignedToMe, invalid queries parameterized, archive 501); `CursorTest` |
| R34–R37 / E45–E49, E52 | `CaseLifecycleIT$Transitions` (E45, E46 scenarios; rejections parameterized) |
| R38/R39 / E50, E51, E53 | `CaseLifecycleIT$Assignment#assign` (parameterized) |
| R21/R22 channel | `CaseRulesTest#channelIsDerivedFromTheCaller` |
