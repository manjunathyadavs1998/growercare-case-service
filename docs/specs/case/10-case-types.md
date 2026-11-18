# SPEC-CASE-10: Case types and their statuses for the UI
- **Requirements:** PRD M4 (case form, queue filters and status chips without hard-coded workflow data, convention 4); SPEC-FE-02 D4, SPEC-FE-03 D3
- **Status:** Implemented
- **Owner:** case-service
- **IDs:** rules CASE-R220+, examples CASE-E220+

## 0. Decisions
| # | Decision | Why |
|---|---|---|
| D1 | **`GET /case-types`** returns the active case types with their default SLA and their statuses (code, initial, closed, display order). Codes only: the UI translates codes through its message catalogues (FE-01 D7), so names in en/hi/mr live with the UI | The workflow stays data (ref.case_type / ref.case_status); the UI can build the type picker, filters and status chips |
| D2 | **Any signed-in user with `case:read`** (growers included); the list is the same for everyone in Phase 1 | Reference data, not personal data; per-country or per-role type lists can come with control-plane profiles (Phase 2) |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R220 | `case:read` | `GET /case-types` | 200 active types ordered by code, each with statuses in display order; `Cache-Control: max-age=300` |
| CASE-R221 | an inactive type | list | not included |
| CASE-R222 | no `case:read` | list | 403 |

## 2. Examples
| Example | Rule | Actor | Expected |
|---|---|---|---|
| CASE-E220 | R220 | grower.in1 · care.in | PRODUCT_COMPLAINT with SUBMITTED (initial) … CLOSED (closed); the same list for both |
| CASE-E221 | R221 | care.in, a type set inactive (test) | absent |
| CASE-E222 | R222 | a token without `case:read` (test principal) | 403 |

## 3. API contract
```
GET /case-types   200 [{caseType, defaultSlaHours, statuses: [{status, initial, closed, order}]}] · 403
```

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R220–R222 / E220–E222 | `CaseTypesIT` |
