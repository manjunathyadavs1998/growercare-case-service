# SPEC-CASE-09: Allowed transitions in the case view
- **Requirements:** CASE-1/4 (typed workflows), Phase 1 PRD UI scope (§8: "transitions offered by the workflow"), UI readiness before M4
- **Status:** Implemented
- **Owner:** case-service
- **IDs:** rules CASE-R190+, examples CASE-E190+

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **`GET /cases/{id}` returns `allowedTransitions`** for the caller: `[{toStatus, closes}]`, the edges of `ref.case_transition` from the current status that `POST /cases/{id}/transitions` would accept from this caller now (CASE-R34–R36: `case:transition`, the edge's own permission, case in scope) | The UI must not hard-code the workflow; one rule in one place |
| D2 | **The list is advice, not a lock:** the transition endpoint still checks everything (version, permission) when called | Rights can change between reading and acting |
| D3 | **Only on the detail view,** not in lists | Lists stay cheap; the UI asks when a case is opened |
| D4 | **Assignment is reported too:** `canAssign` (boolean) for the caller (CASE-R38) | The same UI question for the other action on the detail screen |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R190 | a case the caller may read | `GET /cases/{id}` | the view has `allowedTransitions` (ordered by `toStatus`) and `canAssign` |
| CASE-R191 | a status/type with edges the caller may take | same | exactly those edges: every listed edge is accepted by `POST …/transitions` (with the current version); every unlisted edge of the type from this status is refused (403 or 422) |
| CASE-R192 | the caller has no `case:transition` (e.g. a grower) · a closed case without reopen edges | same | `[]` |
| CASE-R193 | an edge needing an extra permission the caller lacks (e.g. `compensation:approve`) | same | that edge is not listed |

## 2. Examples
| Example | Rule | Actor | Case | Expected `allowedTransitions` / `canAssign` |
|---|---|---|---|---|
| CASE-E190 | R190/R191 | care.in | new PRODUCT_COMPLAINT (SUBMITTED) | the SUBMITTED edges of PRODUCT_COMPLAINT that care.in holds (e.g. TRIAGED); `canAssign` true (care agents assign, CASE-R38) |
| CASE-E191 | R191 | tm.in | same | its edges; `canAssign` true |
| CASE-E192 | R192 | grower.in1 | its own case | `[]`, `canAssign` false |
| CASE-E193 | R193 | care.in | PRODUCT_COMPLAINT at ROOT_CAUSE_DETERMINED, where proposing compensation needs `compensation:propose` | COMPENSATION_PROPOSED absent, NO_COMPENSATION listed |
| CASE-E194 | R191 | care.in and tm.in (staff who can read a fresh case; agro.in sees only cases assigned to it) × every case type at its initial status | property check | listed ⇔ `POST …/transitions` succeeds (run on a copy of the case per edge) |

## 3. API contract
`Case` (detail) gains `allowedTransitions: [{toStatus, closes}]` and `canAssign`. No new endpoint. OpenAPI regenerated.

## 4. Data & events
None: read from `ref.case_transition` and the caller's grants.

## 5. Security
Computed with the same scope and permission checks as the actions (`CaseAccess`, `PolicyEnforcer`); never wider than what the actions allow.

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R190–R193 / E190–E194 | `AllowedTransitionsIT` (E194: care.in and tm.in × 4 case types, every edge on its own fresh case; lists carry no actions) |
