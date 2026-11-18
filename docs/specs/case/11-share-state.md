# SPEC-CASE-11: Active shares in the case view
- **Requirements:** SPEC-FE-02 R107 follow-up (the grower app reads share state from the trail today), ACC-11
- **Status:** Implemented
- **Owner:** case-service
- **IDs:** rules CASE-R230+, examples CASE-E230+

## 0. Decisions
| # | Decision | Why |
|---|---|---|
| D1 | **`CaseView.sharedWithPartnerOrgIds`** lists the partner orgs the case is actively shared with, **only for the grower and for staff**; a partner sees just its own org if shared (never which other shops see the case) | The UI shows the true state; partners don't learn about each other (ACC-11) |
| D2 | Lists keep omitting it (as `allowedTransitions`, SPEC-CASE-09) | Lists stay cheap |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R230 | the grower or staff | `GET /cases/{id}` | `sharedWithPartnerOrgIds` = active shares (ids as strings), `[]` when none |
| CASE-R231 | a partner | same | `[ownOrg]` if shared with its org, else `[]`; never other orgs |
| CASE-R232 | share · unshare | then read | the list follows at once |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| CASE-E230 | R230 | case shared with 501 and 502; grower.in1 · care.in | `["501","502"]` both |
| CASE-E231 | R231 | retailer.in (501) · retailer2.in (502) | `["501"]` · `["502"]` |
| CASE-E232 | R232 | grower unshares 502 | `["501"]` |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R230–R232 / E230–E232 | `ShareStateIT` |
