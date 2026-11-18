# SPEC-CASE-07: Case search and the territory overview
- **Requirements:** CASE-10 (search by grower phone, batch, product, status, territory, SLA risk), DSH-2 (dashboards obey the same scope), Phase 1 PRD journeys **J5** (care finds the grower's cases when they call) and **J9** (territory overview), M3
- **Status:** Implemented
- **Owner:** case-service (grower lookup by phone stays in grower-service)
- **IDs:** rules CASE-R200+, examples CASE-E200+

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Phone → grower stays in grower-service** (`POST /growers/search`, SPEC-GRW-01 R30): only grower-service holds the phone key. The UI then lists that grower's cases with the new `growerId` filter. case-service never sees a phone number | One place for the phone key (PRV-5); no second HMAC key to distribute |
| D2 | **New list filters `growerId`, `productId`, `batchNo`,** combined with the existing ones (status, type, territory, queue, SLA state, assigned to me, created from) and always inside the caller's scope. A grower's cases all live in one shard (they are routed by grower), so `growerId` is a single-shard query | CASE-10; the grower filter is the call-centre path and must be fast |
| D3 | **A filter never reveals what scope hides:** filtering by a grower or product outside the caller's scope returns an empty page, not 404 or 403 (a list, not a resource) | No existence leak through search |
| D4 | **`GET /cases/overview`** returns counts for the caller's `dashboard:read` scope (optionally narrowed to one territory inside it): open cases by status and by type, SLA `AT_RISK` and `BREACHED` (SlaRules), and workload (open cases per assignee). Only grants that carry `dashboard:read` count, with the same scope rules as the case list (DSH-2: no dashboard back door) | J9; one rule for lists and numbers |
| D5 | **Counts are computed live across the shards in Phase 1,** with a 30-second cache per caller scope. The materialised summary (or Snowflake serving layer) is a later optimisation | Phase 1 volume is small; correctness first |
| D6 | **No PII in the overview:** assignees by user id only (the UI resolves staff names through IAM), no grower data | Dashboards must not become a way around masking |

## 1. Behaviour (rules)
### Search filters
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R200 | caller with `case:read` | `GET /cases?growerId=G` | that grower's cases inside the caller's scope, newest first; others' cases never |
| CASE-R201 | same | `GET /cases?productId=P[&batchNo=B]` | cases about that product (and batch) inside the scope |
| CASE-R202 | a grower or product outside the caller's scope | filter | 200 with an empty page (D3) |
| CASE-R203 | `growerId` or `productId` not an id · `batchNo` not `^[A-Z0-9-]{4,32}$` · `batchNo` without `productId` | list | 400 naming the field |
| CASE-R204 | filters combined with the existing ones and the cursor | list | all filters apply together; the cursor keeps them (a cursor from another filter set is 400) |

### Territory overview
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R210 | caller with `dashboard:read` | `GET /cases/overview[?territoryId=T]` | 200 `{scope: {countryCodes, territoryIds}, asOf, openByStatus: {status: n}, openByType: {type: n}, sla: {atRisk, breached}, workload: [{assigneeUserId, open}], unassigned}` over open cases in that scope |
| CASE-R211 | `territoryId` outside the caller's dashboard scope · unknown | overview | 404 |
| CASE-R212 | caller without `dashboard:read` (care agent, agronomist, grower, retailer) | overview | 403 |
| CASE-R213 | the same caller | `GET /cases` (filtered like the overview) vs the overview | every count equals what the case list shows with the matching filters (property check) |
| CASE-R214 | a case changes | overview within 30 s | reflects it (D5 cache) |

## 2. Examples
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| CASE-E200 | R200 | care.in | G1's id (found via grower-service by phone) | G1's cases only |
| CASE-E201 | R200/R202 | grower.in2 · care.br | `growerId=G1` | empty page (both) |
| CASE-E202 | R201 | care.in | `productId=9001` · `productId=9001&batchNo=AZX2604-117` | complaints about 9001 · only that batch |
| CASE-E203 | R203 | care.in | `growerId=abc` · `batchNo=x` · `batchNo` alone | 400 `growerId` · `batchNo` · `batchNo` |
| CASE-E204 | R204 | care.in | `growerId=G1&status=SUBMITTED&limit=1`, follow the cursor · reuse it with `status=TRIAGED` | pages of G1's SUBMITTED cases · 400 `cursor` |
| CASE-E210 | R210 | tm.in (Maharashtra: 1001–1003) | overview · `territoryId=1002` | counts over Maharashtra · over Pune only |
| CASE-E211 | R211 | tm.in | `territoryId=1005` (Karnataka) · `999999` | 404 · 404 |
| CASE-E212 | R212 | care.in · agro.in · grower.in1 · retailer.in | overview | 403 |
| CASE-E213 | R213 | tm.in | overview vs the case list per status, per type, per SLA state, per assignee | equal |
| CASE-E214 | R214 | tm.in | create a case in Pune, wait up to 30 s | `openByStatus.SUBMITTED` + 1 |
| CASE-E215 | **smoke** (PRD J5, J9) | care.in finds G1 by phone in grower-service, lists G1's cases; tm.in opens the overview | through the edge gateway (SPEC-EDGE-01) | 200s; tm.in's numbers match its list |

## 3. API contract
```
GET /cases?growerId=&productId=&batchNo=&…existing filters…     200 CasePage · 400
GET /cases/overview?territoryId=                               200 Overview · 403 · 404
Overview = {scope: {countryCodes[], territoryIds[]}, asOf, openByStatus: {}, openByType: {}, sla: {atRisk, breached},
            workload: [{assigneeUserId, open}], unassigned}
```

## 4. Data & events
- Indexes per shard: grower search uses `cases_grower_ix (grower_id, case_id DESC)` and batch search `cases_batch_ix (product_id, batch_no)` (both since V1). Product-only search has no index of its own in Phase 1: it runs inside the scope (territory/status indexes, 90-day hot window). A `cases_product_ix` needs an **online** build (`CREATE INDEX ... ON ONLY` the parent, `CONCURRENTLY` per weekly partition, then `ATTACH`), which the migration tooling cannot do yet; it is a follow-up, not a plain locking `CREATE INDEX` on every shard.
- No new events. Overview cache: in-process per scope key, `gc.case.overview-cache-ttl` (30 s; tests use 0 s).
- Cursors are `<keyset cursor>.<filter fingerprint>` (R204).

## 5. Security
| Concern | Rule |
|---|---|
| Search | always inside `case:read` scope; out-of-scope filters give empty pages (D3) |
| Overview | `dashboard:read` grants only, same scope SQL as the list (DSH-2) |
| PII | no phone in case-service; overview has no grower data (D6) |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R200–R204 / E200–E204 | `CaseSearchIT` |
| R210–R214 / E210–E214 | `CaseOverviewIT` (E213 as a property check), `OverviewCacheTest` (D5 window) |
| E215 | growercare-local-platform `smoke.sh` (through the gateway) |
