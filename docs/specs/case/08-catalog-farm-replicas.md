# SPEC-CASE-08: Farm and catalog replicas
- **Requirements:** CASE-1/2 (cases reference product, batch, farm), DATA-1 (owning services), Phase 1 PRD journeys **J3/J4** and M2 ("case uses farm and catalog replicas, seeds retired")
- **Status:** Implemented
- **Owner:** case-service. Sources: grower-service farms (SPEC-GRW-02), catalog-registry-service (SPEC-CAT-01)
- **Builds on:** SPEC-CASE-06 (the grower replica: event + read-through pattern, D9 scope-first, D10 error mapping)
- **IDs:** rules CASE-R160+, examples CASE-E160+ (CASE-07 is reserved for phone search)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Same pattern as the grower replica:** events keep `ref.farm`, `ref.product`, `ref.product_registration` and `ref.product_batch` fresh; a case creation that names something not yet replicated **reads through** the owner's internal API once and stores it | Works when an event is late (a farm added a second before the case) without a synchronous dependency on every create |
| D2 | **Batches are replicated on demand only:** read-through on case creation, refreshed by `catalog.batch.events` only for batches already in the replica | Millions of batches; case-service needs the few that cases name |
| D3 | **Upserts are version-guarded** (`… WHERE ref.x.version < EXCLUDED.version`), like `ref.grower` | Out-of-order events never roll a row back |
| D4 | **A case accepts only an ACTIVE farm of the case's grower:** an archived farm is 422 `case.farm-not-active`; another grower's farm stays 422 `case.farm-not-of-grower` (CASE-R25) | SPEC-GRW-02 D5 |
| D5 | **Product rules stay CASE-R25:** registered (`REGISTERED`, valid) in the case's country, batch of that product. A batch unknown to catalog-registry is 422 `case.batch-unknown` | Unchanged behaviour, now on real data |
| D6 | **Seeds retired, rows kept:** the dev-seed products 9001–9003 and farms 80011–80014 stay as version -1 rows (R182) (existing cases reference them by FK) and are overwritten by the owners' events (catalog and grower dev seeds use the same ids). No new product or farm seed rows in case-service | PRD M2; FKs forbid deleting the old rows |
| D7 | **Read-through failures:** owner unreachable → 503 `DEPENDENCY_UNAVAILABLE` (nothing written); owner says 404 → the normal 422 rule (farm-not-of-grower / product-not-registered / batch-unknown) | Same mapping as CASE-06 R142/D10 |
| D8 | **Scope first** (CASE-06 D9): the grower is checked before any read-through, so an out-of-scope caller never triggers a call to another service | No probing other services through case-service |

## 1. Behaviour (rules)
### Events
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R160 | `grower.farm.created` / `updated` / `archived` | consumed (group `case-service.farm-replica`) | fetch `GET /internal/growers/{growerId}/farms/{farmId}`; upsert `ref.farm` (name, area, location, status, version); inbox row. A farm whose grower is not replicated yet refreshes the grower first (CASE-06 R130) |
| CASE-R161 | `catalog.product.upserted` / `catalog.registration.changed` | consumed (group `case-service.catalog-replica`) | fetch `GET /internal/products/{id}`; upsert `ref.product` and each `ref.product_registration` row, each guarded by its own version (SPEC-CAT-01 R20 view) |
| CASE-R162 | `catalog.batch.upserted` for a batch in `ref.product_batch` · not in it | consumed | fetch and upsert · ignore (D2), inbox row either way |
| CASE-R163 | duplicate event · older version | consumed | no change (inbox / D3) |
| CASE-R164 | owner returns 404 for an event's entity · malformed event | consumed | no change, inbox row · DLQ (non-retryable) |
| CASE-R165 | owner unreachable | consumed | retried, then the consumer's DLQ (`dlq.case-service.farm-replica` / `dlq.case-service.catalog-replica`, as CASE-06 R137) |

### Case creation
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R170 | farm F of grower G not in `ref.farm` | `POST /cases` for G with F | read-through; F stored; normal creation (201) when F is ACTIVE and G's |
| CASE-R171 | F archived | create | 422 `case.farm-not-active` (D4) |
| CASE-R172 | product P not in `ref.product` | create | read-through product + registrations; then CASE-R25 |
| CASE-R173 | batch B of P not in `ref.product_batch` | create | read-through; catalog 404 → 422 `case.batch-unknown` (D5) |
| CASE-R174 | farm or catalog service unreachable during a needed read-through | create | 503 `DEPENDENCY_UNAVAILABLE`, nothing written (D7) |
| CASE-R175 | caller cannot see grower G | create naming any farm/product/batch | 404 as before, **no** read-through call (D8) |

### Migration and seed
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R180 | `ref.farm`, `ref.product`, `ref.product_registration`, `ref.product_batch` | expand migration | gain `version NOT NULL DEFAULT -1` (`integer` on `ref.farm`, `bigint` on the catalog tables; -1 marks pre-existing rows, R182); `ref.farm` gains `status` (ACTIVE/ARCHIVED, default ACTIVE); the runtime role may INSERT/UPDATE these four tables (still read-only on the other `ref` tables) |
| CASE-R181 | dev seed | migration | no new seed rows; existing 9001–9003 / 80011–80014 rows unchanged except their version, -1 (D6, R182) |
| CASE-R182 | a replica row that existed before the expand migration (seed copy, D6) | the owner's first event or read-through, at any owner version (0 included) | the owner's state replaces it: pre-existing rows carry version -1 |

## 2. Examples
**Fixture:** case-service dev seed; stubs for grower-service farms and catalog-registry (record calls, can fail, return 404). Fresh ids from `nextGrowerIdInTestShards` / test-only farm and product ids.

| Example | Rule | Input | Expected |
|---|---|---|---|
| CASE-E160 | R160 | `grower.farm.created` for new farm F1 of G1 (stub: ACTIVE, version 0) | `ref.farm` row F1 with the stub values; one inbox row |
| CASE-E161 | R160/R163 | `grower.farm.archived` F1 (stub version 1) · the created event again (version 0) | F1 ARCHIVED, version 1 · unchanged |
| CASE-E162 | R161 | `catalog.registration.changed` 9002 IN (stub: SUSPENDED) | `ref.product_registration` IN/9002 SUSPENDED; then grower.in1's case with 9002 → 422 `case.product-not-registered` |
| CASE-E163 | R162 | `catalog.batch.upserted` for `AZX2604-117` (in replica) · for `NEW-0001` (not) | refreshed · ignored, no stub call |
| CASE-E164 | R164 | event for a farm the stub 404s · malformed payload | no row, inbox row · DLQ |
| CASE-E165 | R165 | farm stub failing | retried, then on `dlq.case-service.farm-replica` |
| CASE-E170 | R170 | grower.in1 creates a case with new farm F2 (stub: G1's, ACTIVE), not replicated | 201; F2 in `ref.farm`; stub called once |
| CASE-E171 | R171 | grower.in1 creates a case with archived F1 | 422 `case.farm-not-active` |
| CASE-E172 | R172 | grower.in1 creates a case with new product P (stub: registered IN) and its batch | 201; P, its registration and batch replicated |
| CASE-E173 | R173 | grower.in1, product 9001, batch `NOPE-0001` (stub 404) | 422 `case.batch-unknown` |
| CASE-E174 | R174 | catalog stub failing, new product · farm stub failing, new farm | 503 (both), nothing written |
| CASE-E175 | R175 | grower.in2 names G1 with a new farm | 404, no stub call |
| CASE-E166 | R182 | `grower.farm.updated` for seed farm 80014 (stub: version 0, renamed) | `ref.farm` 80014 renamed, version 0 |
| CASE-E170b | R170 | grower.in1 names a new farm grower-service does not know for G1 | 422 `case.farm-not-of-grower` |
| CASE-E172b | R172/R25 | grower.in1 names a new product withdrawn in IN (stub) | 422 `case.product-not-registered` after the read-through |
| CASE-E180 | R180 | app role | UPDATE `ref.farm` (allowed) · UPDATE `ref.territory` | ok · permission denied |
| CASE-E181 | **smoke** (PRD J4) | grower.in1 raises a complaint with a product found via catalog search, a valid batch, the farm added in GRW-E222 | 201; an unknown batch → 422 `case.batch-unknown` |

## 3. API contract
No new endpoint. `POST /cases` gains 422 `case.farm-not-active` (R171); 503 on read-through failure exists since CASE-06. OpenAPI regenerated.

## 4. Data & events
- Common migration (expand): `version` on the four tables (pre-existing rows -1, R182), `ref.farm.status`, grants (R180). Upserts `INSERT … ON CONFLICT … DO UPDATE … WHERE version < EXCLUDED.version`.
- Consumers: `grower.farm.events`, `catalog.product.events`, `catalog.batch.events` with inbox dedup and per-topic DLQ (`CaseEventsConfig.DLQ_BY_SOURCE`).
- New variable `CATALOG_BASE_URL`; farms use the existing `GROWER_BASE_URL`.

## 5. Security
| Concern | Rule |
|---|---|
| Scope | R175: scope before read-through |
| Service calls | client credentials (`case-service`), audience gc-internal; catalog-registry and grower-service allow-list `case-service` |
| Location | `ref.farm` keeps the exact point (case-service applies its own clearance rules when showing farms, unchanged) |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R160–R165, R182 / E160–E166 | `FarmCatalogConsumerIT` (real Kafka; owners stubbed by `StubReferenceOwners`) |
| R170–R175 / E170–E175, E170b, E172b | `CaseCreationCatalogIT` |
| R180 / E180 | `CaseSchemaMigrationIT` (`appRoleWritesOnlyTheGrowerReplica` rows) |
| E181 | growercare-local-platform `smoke.sh` |
