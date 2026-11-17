# SPEC-CASE-06: Grower replica from grower-service
- **Requirements:** GRW-1 (case side), CASE-1/2 (cases raised for real growers), ACC-1/ACC-11 (OWN and ORG scope need the replica), PRV-1/PRV-5, ADR-001 (database per service), ADR-012 (outbox events), SPEC-PLAT-04 (consumers), SPEC-GRW-01 R42 (internal view) and D6
- **Status:** Agreed (2026-11-17)
- **Owner:** case-service (consumer); grower-service is the owner of the data

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Event-carried notification, then fetch.** `grower.registered` / `grower.updated` (topic `grower.grower.events`, key = growerId) carry ids only, so the consumer calls grower-service `GET /internal/growers/{id}` (client credentials as `case-service`) and upserts `ref.grower` from that answer | Events stay free of PII (SPEC-GRW-01 §4); the fetch always returns the **current** state, so the order in which events arrive does not matter |
| D2 | **Read-through on a miss.** When case creation names a grower the replica does not know yet, case-service fetches it from grower-service in the request and upserts it before continuing | A grower who registers and raises a case a second later must not get a 404 because the event is still in flight; growers that exist without an event (grower-service's dev seed) are covered too |
| D3 | **Version guard.** `ref.grower` gains `version` (grower-service's version). An upsert only replaces a row whose stored version is **lower** (`ON CONFLICT … WHERE ref.grower.version < EXCLUDED.version`) | A slow fetch that started earlier can never overwrite a newer row written by a faster one |
| D4 | **Only ACTIVE growers get cases.** The replica stores grower-service's status, including `PENDING_CONSENT`. Creating a case for a grower who is in scope but not ACTIVE is **422 `case.grower-not-active`** (not 404: the caller may see the grower, so hiding it would only confuse) | Assisted growers have not confirmed by OTP yet (SPEC-GRW-01 R11; consent, PRV-1); processing their case before consent is what the consent step is for |
| D5 | **Consumer failures follow SPEC-PLAT-04:** the inbox makes redelivery a no-op; grower-service unreachable or 5xx → 3 retries with backoff, then `dlq.case-service.grower-replica`; a malformed record → DLQ at once; a grower that grower-service answers 404 for → ignored and recorded in the inbox (nothing to replicate). The DLQ topic is chosen by the **source topic**, so each consumer keeps its own DLQ | Same guarantees as the grant consumer; one bad grower never blocks the topic |
| D6 | **Dev seed moves to grower-service's ids.** A dev-seed migration links users 1–3 and the assisted grower to grower-service's G1–G4 ids (same names, territories, partners, last 4) and gives them **new farms 80011–80014** (the old farms stay with the old rows: cases raised on them reference `(farm_id, grower_id)` through a composite FK, found on a local stack with existing cases). The 70001-style rows stay for cases already created locally, but become `INACTIVE` with no user, so `user_id` stays unique and nobody can raise new cases on them. Tests use the G ids from then on | Ends SPEC-GRW-01 D6's temporary state: one grower, one id, in both services |
| D7 | **Reference data the replica needs must exist.** Territory and partner of a replicated grower are foreign keys into case-service's own `ref.territory` / `ref.partner_org`. A grower whose territory or partner case-service does not know is a **reference-data gap**: the consumer sends it to the DLQ (after retries, as a transient failure, because the master may arrive later) and creation answers 503 `DEPENDENCY_UNAVAILABLE` | Silently dropping the partner would break ORG scope (ACC-11); masters are replicated separately (control-plane, IAM) |
| D9 | **Read-through is authorised before it runs.** Only a grower id that decodes to a country in which the caller may create cases (home country, or a country of a `case:create` grant) is fetched at all; the fetched grower must then pass the full creation scope check **before** anything is stored. Out of scope is always 404 — no outbound call for a foreign country, no replica write, and never a 503 that would tell "exists" from "unknown" | Without this, anyone with `case:create` could make case-service fetch and store arbitrary growers, and 404-vs-503 would be an existence oracle (review finding on #6) |
| D10 | **Only foreign-key gaps are transient.** A territory or partner unknown to case-service (FK violation, SQLState 23503) is the D7 gap: retried, then DLQ / 503. Any other integrity conflict (e.g. the grower's user is already linked to another grower in the replica) will not heal by waiting: the consumer sends it to the DLQ **at once**, case creation answers **409 `DUPLICATE`** | Operators see the real cause; no pointless retries; no misleading 503 (review finding on #6) |
| D8 | **The phone stays an HMAC.** The replica stores `phone_hmac` and `phone_last4` exactly as grower-service gives them (same key), never the phone | Prepares find-by-phone in case-service (CASE-10) without a second copy of the key or the number |

## 1. Behaviour (rules)
### Event consumer (`grower.grower.events`, group `case-service.grower-replica`)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R130 | `grower.registered` or `grower.updated` for grower X | consumed | X is fetched from grower-service and upserted into `ref.grower` (id, country, territory, user, display name, phone HMAC + last 4, preferred partner, status, version); inbox row in the same transaction |
| CASE-R131 | the replica already holds X at version ≥ the fetched one | upsert | the stored row is unchanged (D3) |
| CASE-R132 | the same event delivered twice | consumed | the side effect runs once (inbox, PLAT-R56) |
| CASE-R133 | grower-service answers 404 for X | consumed | nothing written to `ref.grower`; inbox row; offset committed |
| CASE-R134 | grower-service unreachable / 5xx / token refused, or X's territory or partner unknown to case-service (D7) | consumed | retried 3 times, then the record is on `dlq.case-service.grower-replica`; the next record is processed |
| CASE-R135 | value not JSON, no `eventId`/`eventType`, or a grower event whose key/`growerId` is not an id | consumed | straight to `dlq.case-service.grower-replica` (no retries) |
| CASE-R136 | an event type the consumer does not handle (e.g. `grower.plot.changed`) | consumed | ignored, inbox row, offset committed (PLAT-R59) |
| CASE-R137 | the grant consumer and the grower consumer both fail | — | each record goes to its **own** DLQ topic (by source topic) |

### Case creation with the replica
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R140 | grower X not in `ref.grower` | `POST /cases` names X | read-through: X is fetched and upserted (as R130), then creation continues as usual |
| CASE-R141 | grower-service answers 404 for X | `POST /cases` | 404 `NOT_FOUND` (as an unknown grower today) |
| CASE-R142 | grower-service unreachable / 5xx, or a reference-data gap (D7) | `POST /cases` names an unknown X | 503 `DEPENDENCY_UNAVAILABLE`, nothing written (fail closed) |
| CASE-R143 | X in scope with status `PENDING_CONSENT` or `INACTIVE` | `POST /cases` | 422 `case.grower-not-active`, nothing written |
| CASE-R144 | X known to the replica | `POST /cases` | **no** call to grower-service (the replica is the read path; freshness comes from events) |
| CASE-R145 | a grower self-registers in grower-service (user U) | U raises a case for their own grower | 201 (read-through links U); U's case list then shows it (OWN scope via `ref.grower.user_id`) |
| CASE-R146 | grower X not in the replica, and X's id decodes to a country where the caller cannot create cases | `POST /cases` | 404; grower-service is **not** called (D9) |
| CASE-R147 | grower X not in the replica, fetched, but outside the caller's creation scope | `POST /cases` | 404; nothing stored; also when X has a reference-data gap (never 503 for an out-of-scope grower, D9) |
| CASE-R148 | the fetched grower conflicts with the replica (its user already linked to another replica grower) | consumed · `POST /cases` | DLQ at once, no retries · 409 `DUPLICATE` (D10) |

### Replica and seed
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R150 | `ref.grower` | expand migration | gains `version integer NOT NULL DEFAULT 0`; status CHECK adds `PENDING_CONSENT`; the runtime role may INSERT/UPDATE `ref.grower` (it stays read-only on every other `ref` table) |
| CASE-R151 | dev seed (local and tests only) | migration | G1–G4 rows with grower-service's ids and data (D6); new farms 80011–80014 belong to them (80001–80004 stay with the old rows); the 70001…70004 rows become `INACTIVE` with `user_id NULL` |

## 2. Examples
**Fixture:** grower-service's dev seed (SPEC-GRW-01 §2): G1 Ramesh Patil (user 1, IN, 1002, partner 501), G2 Sunita Rao (user 2, IN, 1003, 502), G3 Lakshmi (assisted, PENDING_CONSENT, IN, 1005, onboarded via 502), G4 Joao Silva (user 3, BR, 2002). In tests, grower-service is a stub (`GrowerDirectory` port) returning these and whatever a test registers. Tests also add **K1**, a test-only ACTIVE grower in Karnataka (1005, farm 80005), because G3 there is PENDING_CONSENT and many existing rows (routing, territory scope) need a Karnataka grower who can have cases; K1 is inserted by the test database only, never by the dev seed.

| Example | Rule | Given / input | Expected |
|---|---|---|---|
| CASE-E130 | R130 | `grower.registered` for a new grower N (stub: ACTIVE, IN, 1003, user 960, partner 502, version 0) | `ref.grower` row for N with exactly those values; one inbox row |
| CASE-E131 | R130 | `grower.updated` for G2 (stub now version 3, language irrelevant, territory 1002) | G2's row: territory 1002, version 3 |
| CASE-E132 | R131 | replica holds N at version 5; event delivers a fetch that answers version 4 | row still at version 5 |
| CASE-E133 | R132 | the same `grower.registered` twice | stub called once; one inbox row |
| CASE-E134 | R133 | event for an id the stub answers 404 for | no `ref.grower` row; inbox row; next record processed |
| CASE-E135 | R134 | stub always 503 · stub answers a grower with territory 9999 (unknown) · with partner 999 (unknown) | each record on `dlq.case-service.grower-replica` after 3 attempts; the next record is processed |
| CASE-E136 | R135 | value `not-json` · envelope without `eventId` · `growerId` "abc" | each on the DLQ at once (`MalformedEventException`), no retries |
| CASE-E137 | R136 | `grower.plot.changed` | no fetch; inbox row |
| CASE-E138 | R137 | a failing grant event and a failing grower event | one record on `dlq.case-service.iam-grant-cache`, one on `dlq.case-service.grower-replica` |
| CASE-E140 | R140 | care.in creates a case for N, not yet replicated (stub: ACTIVE) | 201; `ref.grower` row for N; stub called once |
| CASE-E141 | R141 | care.in creates a case for an id the stub answers 404 for | 404 `NOT_FOUND` |
| CASE-E142 | R142 | stub down · stub answers a grower with an unknown territory | 503 `DEPENDENCY_UNAVAILABLE`; no case, no replica row |
| CASE-E143 | R143 | care.in creates a case for G3 (PENDING_CONSENT, in scope) · for 70001 (INACTIVE after D6) | 422 `case.grower-not-active` (both) |
| CASE-E144 | R144 | care.in creates a case for G1 (replicated) | 201; stub **not** called |
| CASE-E145 | R145 | test grower user 961 registered in the stub as N2 (ACTIVE, user 961); 961 creates a case for N2, then lists its cases | 201; the list contains the case |
| CASE-E147 | R146 | care.br names an IN grower unknown to the replica | 404; stub not called |
| CASE-E148 | R147 | tm.in (Maharashtra) names an unknown Karnataka grower · the same grower with an unknown partner | 404 (both, never 503); no replica row |
| CASE-E149 | R148 | the stub answers a grower whose user is already linked to another replica grower: as an event · as `POST /cases` by care.in | DLQ after one attempt (cause `ConflictException`) · 409 `DUPLICATE` |
| CASE-E146 | R4/R1 (regression) | the isolation matrix of SPEC-CASE-01 (E01–E09) with the G ids instead of 70001… | same visible sets as before |
| CASE-E150 | R150 | app role: `INSERT`/`UPDATE ref.grower` · `UPDATE ref.territory` · `DELETE FROM ref.grower` | allowed · permission denied · permission denied |
| CASE-E151 | R151 | dev seed | G1–G4 present, ACTIVE except G3 (PENDING_CONSENT); farms 80011, 80012, 80014, 80013 belong to G1, G2, G3, G4; 80001/80004 still belong to 70001/70004; 70001–70004 INACTIVE, `user_id` NULL |
| CASE-E152a | **smoke** | grower.in1 raises a case for G1 by its grower-service id | 201 (the seed is aligned, D6) |
| CASE-E152b | **smoke** | care.in registers an assisted grower in grower-service (PENDING_CONSENT, smoke GRW-E21), then raises a case for it in case-service | 422 `case.grower-not-active`: case-service learned the new grower across services (event or read-through) and respects its consent status |

## 3. API contract
No new endpoint. `POST /cases` gains 422 `case.grower-not-active` (R143) and 503 on a read-through failure (R142). OpenAPI regenerated.

Outbound: `GET {GROWER_BASE_URL}/internal/growers/{id}` with a client-credentials token (Keycloak client `case-service`, audience `gc-internal`; grower-service allow-lists `case-service`, SPEC-GRW-01 R42). New required variable **`GROWER_BASE_URL`** (no default, SPEC-PLAT-06).

## 4. Data & events
- **`ref.grower`** (common migration, expand): `+ version`, status CHECK `+ PENDING_CONSENT`, runtime role `INSERT, UPDATE` on this table only. Upsert: `INSERT … ON CONFLICT (grower_id) DO UPDATE … WHERE ref.grower.version < EXCLUDED.version`.
- **Consumed:** `grower.grower.events` (`grower.registered`, `grower.updated`), consumer group `case-service.grower-replica`, inbox `msg.inbox`.
- **DLQ:** `dlq.case-service.grower-replica`; the error handler picks the DLQ by source topic (the grant consumer keeps `dlq.case-service.iam-grant-cache`).
- **Published:** none.
- **Not in this spec:** farms (SPEC-GRW-02 owns them; `ref.farm` stays seeded), erasure (`ERASED`, DSAR), find-by-phone in case-service (CASE-10).

## 5. Security
| Concern | Rule |
|---|---|
| Least privilege | the runtime role writes `ref.grower` only (E150); every other replica stays read-only |
| Service-to-service | read-through and consumer use one client-credentials token (cached until expiry); grower-service is reached only over the network (`GROWER_BASE_URL`) |
| Fail closed | grower-service down during creation → 503, never a case for an unverified grower (R142) |
| PII | events carry ids; the replica holds the display name (needed for R30 masking) and the phone only as HMAC + last 4 (D8) |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R130–R137 / E130–E138 | `GrowerReplicaConsumerIT` (Testcontainers Kafka + Postgres, stubbed `GrowerDirectory`), parameterized like `GrantCacheConsumerIT` |
| R140–R148 / E140–E149 | `CaseCreationReplicaIT`; E149 consumer row in `GrowerReplicaConsumerIT` |
| E146 | `CaseIsolationIT` (fixture ids switched to G1–G4) |
| R150–R151 / E150–E151 | `CaseSchemaMigrationIT` (new rows) |
| E152a, E152b | growercare-local-platform `smoke.sh` |
| client | `GrowerDirectoryClientTest` (404 → empty, 5xx / unreadable / token refused → 503), like `IamPrincipalClientTest` |
