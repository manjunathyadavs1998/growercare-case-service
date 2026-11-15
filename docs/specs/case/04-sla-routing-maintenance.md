# SPEC-CASE-04: SLA clocks, rule-based routing, shard maintenance jobs
- **Requirements:** CASE-1 (SLA per type), CASE-4 (routing rules, phase 1 = rules only), CASE-10 (SLA-risk filter), STW (adverse-incident regulatory clock, alerts at 50/75/90%), ADR-004 (weekly partitions)
- **Status:** Implemented (agreed 2026-11-15)
- **Owner:** case-service
- **Depends on:** SPEC-CASE-02 (lifecycle), SPEC-CASE-01 (scope)

## 0. Decisions to agree
| # | Decision | Alternative (not chosen) |
|---|---|---|
| D1 | **SLA hours per country × case type** come from a new table `ref.case_sla_policy`; the case type's `default_sla_hours` is the fallback. Seed values for adverse incidents are illustrative and must be **validated with Regulatory/Legal per country** | Hard-coded hours (blocks country variation) |
| D2 | **Durable timers** in a new shard table `case_timer` (partitioned by `case_id` like every case-keyed table, so purging stays per week): one row per threshold (**50%, 75%, 90% warning; 100% breach**), created in the create transaction | A cron that scans all cases every minute (expensive at 200M cases/day) |
| D3 | **A timer poller** claims due timers per shard with `FOR UPDATE SKIP LOCKED` (safe with many replicas, no extra library), marks them fired, and writes activity + an outbox event (`case.sla.warning` / `case.sla.breached`), all in one transaction. Fired at-least-once, recorded exactly once | ShedLock / Quartz (extra dependency, single-runner) |
| D4 | **Closing a case cancels pending timers; reopening re-arms** a fresh SLA from the reopen time. **No pause in waiting statuses (e.g. NEED_INFO) in v1**; a pause/resume rule comes with compliance packs | Pause now (needs per-country rules first) |
| D5 | **Routing assigns a queue, not a person.** On create, the most specific matching rule in `ref.routing_rule` (country, case type, BU, territory incl. ancestors) sets `cases.queue_code`; every country has a fallback queue. Person assignment stays manual (SPEC-CASE-02 R38). Workload scoring is CASE-4 phase 3 | Auto-assign to a person (needs IAM staff directory + workload, phase 3) |
| D6 | **SLA state is computed, not stored:** `ON_TRACK`, `AT_RISK` (≥ 75% elapsed, open), `BREACHED` (past due, open), `MET` / `MISSED` (closed before/after due). Exposed on CaseView and as a list filter | A stored column (must be updated by a job; can drift) |
| D7 | **Maintenance jobs** (`@Scheduled`, disabled in tests, triggered directly there): <br>(a) daily `ensure_week_partitions(1, 8)` per shard; <br>(b) daily delete of `case_idempotency` rows older than 7 days; <br>(c) hourly check that `*_default` partitions are empty: **ERROR log + metric `gc.partition.default.rows`** if not. <br>Each shard runs under `pg_try_advisory_xact_lock`, so replicas never do the same shard twice | A Kubernetes CronJob per task (later, when jobs move out of the service) |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R80 | a case is created | — | `sla_due_at = created + hours`; hours = `ref.case_sla_policy(country, type)` if present, else `case_type.default_sla_hours` |
| CASE-R81 | a case is created | — | 4 timers: WARN_50, WARN_75, WARN_90 at those fractions of the SLA, BREACH at `sla_due_at`; same transaction as the case |
| CASE-R82 | a timer is due and not fired, the case is open | poller runs | timer marked fired; activity `SLA_WARNING` (with `threshold`) or `SLA_BREACHED`; outbox `case.sla.warning {caseId, threshold, slaDueAt}` / `case.sla.breached {caseId, slaDueAt}` |
| CASE-R83 | the same due timer | two pollers run concurrently, or the poller runs twice | fired **exactly once**: one activity row, one event |
| CASE-R84 | a case enters a closed status | — | its unfired timers are cancelled (`cancelled_at`), and never fire |
| CASE-R85 | a closed case is reopened (CLOSED → REOPENED) | — | new `sla_due_at` = reopen time + hours, new set of 4 timers |
| CASE-R86 | any case | CaseView / list | `slaState` ∈ {ON_TRACK, AT_RISK, BREACHED, MET, MISSED} per D6 |
| CASE-R87 | staff | `GET /cases?slaState=AT_RISK\|BREACHED` | only cases in that state (and in scope) |
| CASE-R88 | a case is created | — | `queue_code` = the matching routing rule with the **lowest `priority` number**. A rule matches when each non-null column (case type, BU, territory) equals the case's; a territory rule also matches cases in **descendant** territories. Activity `ROUTED`, outbox `case.routed {caseId, queueCode}` |
| CASE-R89 | no rule matches | — | the country's fallback queue (seed guarantees one per active country; a startup check fails otherwise) |
| CASE-R90 | staff | `GET /cases?queue=<code>` | only cases of that queue (and in scope); unknown queue code → 400 |
| CASE-R91 | partition job runs | — | every shard has partitions up to 8 weeks ahead; re-running creates nothing (idempotent) |
| CASE-R92 | idempotency cleanup runs | — | rows older than 7 days are deleted; newer rows stay, so a retry within 7 days still replays |
| CASE-R93 | a `*_default` partition contains rows | check runs | ERROR log naming the shard/table + gauge `gc.partition.default.rows > 0` (alerting hook) |
| CASE-R94 | two service replicas | the same job runs on both | each shard is processed by one replica only (advisory try-lock) |
| CASE-R95 | one shard fails during a job (SLA poller or maintenance) | the job runs | that shard is logged at ERROR and skipped; every other shard is still processed in the same run, and the failed shard is retried on the next run |

## 2. Examples (each row = one parameterized test row)
**Seeded routing rules:** lower priority number = checked first.

| Prio | Country | Case type | BU | Territory | Queue |
|---|---|---|---|---|---|
| 10 | IN | ADVERSE_INCIDENT | — | — | `IN-STEWARDSHIP` |
| 20 | IN | PRODUCT_COMPLAINT | SEEDS | — | `IN-SEEDS-QUALITY` |
| 30 | IN | — | — | 1001 (Maharashtra) | `IN-MH-FIELD` |
| 40 | IN | PRODUCT_COMPLAINT | — | — | `IN-CP-COMPLAINTS` |
| 999 | IN | — | — | — | `IN-CARE` (fallback) |
| 999 | BR | — | — | — | `BR-CARE` (fallback) |

**Seeded SLA policy:**
- IN ADVERSE_INCIDENT = 48 h; BR ADVERSE_INCIDENT = 72 h (both illustrative, D1).
- Defaults: AGRONOMY_ADVICE 48 h, PRODUCT_COMPLAINT 240 h, SEED_PERFORMANCE_CLAIM 360 h, ADVERSE_INCIDENT 72 h.

| Example | Rule | Case (grower / type / BU) | Expected |
|---|---|---|---|
| CASE-E80 | R80 | 70001 (IN) ADVERSE_INCIDENT CP | SLA 48 h (country policy) |
| CASE-E81 | R80 | 70003 (BR) ADVERSE_INCIDENT CP | SLA 72 h (country policy) |
| CASE-E82 | R80 | 70001 PRODUCT_COMPLAINT CP | SLA 240 h (type default) |
| CASE-E83 | R81 | any new case | 4 timers at 50/75/90/100% of the SLA |
| CASE-E84 | R88 | 70001 (Pune ⊂ MH) ADVERSE_INCIDENT | `IN-STEWARDSHIP` (prio 10 beats territory 30) |
| CASE-E85 | R88 | 70002 (Nashik ⊂ MH) PRODUCT_COMPLAINT SEEDS | `IN-SEEDS-QUALITY` (20 beats 30) |
| CASE-E86 | R88 | 70001 (Pune ⊂ MH) PRODUCT_COMPLAINT CP | `IN-MH-FIELD` (territory ancestor match, 30 beats 40) |
| CASE-E87 | R88 | 70004 (Karnataka) PRODUCT_COMPLAINT CP | `IN-CP-COMPLAINTS` (40) |
| CASE-E88 | R89 | 70004 (Karnataka) AGRONOMY_ADVICE | `IN-CARE` (fallback) |
| CASE-E89 | R89 | 70003 (BR) PRODUCT_COMPLAINT | `BR-CARE` |
| CASE-E90 | R82 | WARN_50 timer due (forced in the past) | fired; activity SLA_WARNING threshold 50; 1 event `case.sla.warning` |
| CASE-E91 | R82 | BREACH timer due | activity SLA_BREACHED; 1 event `case.sla.breached`; `slaState` = BREACHED |
| CASE-E92 | R83 | same due timer, poller run twice / two threads | exactly 1 activity, 1 event |
| CASE-E93 | R84 | case closed, then its timers forced due | no activity, no event; timers `cancelled_at` set |
| CASE-E94 | R85 | CLOSED → REOPENED | new due = reopen + hours; 4 new active timers |
| CASE-E95 | R86 | open, 10% elapsed / 80% elapsed / past due; closed before due / after due | ON_TRACK / AT_RISK / BREACHED / MET / MISSED |
| CASE-E96 | R87 | care.in `slaState=BREACHED` | only breached in-scope cases; care.br sees none of them |
| CASE-E97 | R90 | care.in `queue=IN-STEWARDSHIP` · `queue=NOPE` | only that queue · 400 |
| CASE-E98 | R91 | partition job twice | 2nd run creates 0 partitions |
| CASE-E99 | R92 | idempotency rows aged 8 days and 1 day | the 8-day row is deleted, the 1-day row is kept |
| CASE-E100 | R93 | a row forced into `cases_default` | ERROR log + gauge > 0 |
| CASE-E101 | R94 | partition job on 2 threads simultaneously | each shard processed once (sum of created = single-run count) |
| CASE-E102 | R95 | 4 shards; the first / a middle / the last / every shard throws | all 4 shards are attempted; the total is the sum of the shards that succeeded (0 if all fail) |

**Unit examples** (`SlaRulesTest`, `RoutingRulesTest`):
- SLA state boundaries: exactly 75% → AT_RISK; exactly due → BREACHED.
- Timer due-time arithmetic.
- Rule specificity and ancestor matching on an in-memory rule set.

## 3. API changes
| Change | Detail |
|---|---|
| CaseView / CaseSummary | + `queueCode`, + `slaState` |
| `GET /cases` | + `queue` (pattern `^[A-Z]{2}-[A-Z0-9-]{2,40}$`, must exist), + `slaState` (enum) |
| Contracts | `contracts/case-service.openapi.json` regenerated (drift gate) |

## 4. Data & events
- **Common migration V4:**
  - `ref.case_sla_policy (country_code, case_type_code, sla_hours)`, PK (country, type);
  - `ref.case_queue (queue_code PK, country_code, name)`;
  - `ref.routing_rule (rule_id PK, country_code, priority, case_type_code NULL, bu_code NULL, territory_id NULL, queue_code)` with UNIQUE (country, priority);
  - FKs everywhere; plus the seed.
- **Shard migration V4** (expand only, nullable column):
  - `cases.queue_code` (FK `ref.case_queue`) + index `(queue_code, case_id DESC)`;
  - new `case_timer (case_id, timer_id, kind CHECK IN (WARN_50, WARN_75, WARN_90, BREACH), due_at, fired_at, cancelled_at)`, partitioned by `case_id`, PK (case_id, timer_id); partial index `(due_at) WHERE fired_at IS NULL AND cancelled_at IS NULL`;
  - `case_timer` added to `ensure_week_partitions` and given a `_default` partition;
  - `case_activity.activity_type` CHECK extended with `SLA_WARNING`, `SLA_BREACHED`, `ROUTED`.
- **Events:** `case.routed`, `case.sla.warning`, `case.sla.breached` (IDs and codes only).
- **Schema doc:** `docs/backend/01_RDS_SCHEMA.md` updated.

## 5. Security
- No new endpoints. The new filters reuse `case:read` and the scope filter (SPEC-CASE-01).
- Jobs run without a user principal and touch no PII.

## 6. Impact on existing specs
| Spec | Change |
|---|---|
| SPEC-CASE-03 E79 | The activity trail after create + transition becomes `CREATED, ROUTED, STATUS_CHANGED` (was `CREATED, STATUS_CHANGED`). The example row and its test are updated in the same PR |
| SPEC-CASE-02 R20 | Create also sets `queueCode` and arms the timers (same transaction). Its examples are unchanged |

## 7. Traceability
| Rule / Example | Test |
|---|---|
| R80 / E80–E82 | `CaseSlaRoutingIT$SlaAndTimers#slaHoursPerCountryAndType` (parameterized); `SlaRulesTest#countryPolicyOverridesTheTypeDefault` |
| R81 / E83 | `…$SlaAndTimers#caseE83FourTimersAtTheirFractions`; `SlaRulesTest#timersFireAtTheirFractionOfTheSla` (parameterized) |
| R82 / E90, E91 | `…$Poller#dueTimersFireOnce` (parameterized), `#caseE91BreachedCaseReportsBreachedState` |
| R83 / E92 | `…$Poller#caseE92ConcurrentPollersFireExactlyOnce` |
| R84 / E93 · R85 / E94 | `…$Poller#caseE93ClosingCancelsTimers`, `#caseE94ReopenRearmsTheSla` |
| R86 / E95 | `…$SlaStateAndFilters#slaStateOnViewAndFilter` (parameterized, view + filter); `SlaRulesTest#slaStateIsComputed` (boundaries) |
| R87 / E96 · R90 / E97 | `…$SlaStateAndFilters#caseE96BreachedFilterRespectsScope`, `#caseE97QueueFilter` |
| R88, R89 / E84–E89 | `…$Routing#routedToQueue` (parameterized); `RoutingRulesTest` (rules + fallback check) |
| R91–R94 / E98–E101 | `…$Maintenance` (idempotent partitions, idempotency TTL, default-partition gauge, concurrent jobs) |
| R95 / E102 | `PerShardTest#failingShardIsSkipped` (parameterized); used by `SlaTimerPoller.runOnce` and `MaintenanceJobs` |
