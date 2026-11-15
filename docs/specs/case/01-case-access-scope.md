# SPEC-CASE-01: Case access scope and service security
- **Requirements:** ACC-1, ACC-11, ACC-12, BRD §3 personas, ADR-011
- **Status:** Implemented (security layer; case endpoints follow in SPEC-CASE-02)
- **Owner:** case-service + gc-security

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R1 | GROWER (OWN) | reads cases | only cases of growers linked to their user |
| CASE-R2 | partner (ORG) | reads cases | only cases raised via their org, or with an **active** share to their org |
| CASE-R3 | FIELD_AGRONOMIST (ASSIGNED) | reads cases | only cases assigned to them **and** inside their territory/BU scope |
| CASE-R4 | TERRITORY / COUNTRY roles | read cases | cases whose territory is in the expanded scope, or whose country is covered, ∩ BU scope |
| CASE-R5 | any role | — | the SQL `ScopeFilter` and the in-memory `PolicyEnforcer` give identical answers |
| CASE-R6 | GLOBAL roles, expired grants, missing permission | read cases | nothing (lists empty; object reads 404) |
| CASE-R7 | — | request without a valid token | 401 problem |
| CASE-R8 | valid token, no account | any request | 401 |
| CASE-R9 | IAM unreachable | any request | 503 `DEPENDENCY_UNAVAILABLE` (fail closed) |
| CASE-R10 | service token not allow-listed | user API | 401 |
| CASE-R11 | Redis unavailable | principal lookup | served from IAM (degraded, not failed); unknown accounts never cached |
| CASE-R12 | browser | CORS preflight | allowed only for configured origins; API responses carry CSP, nosniff, no-store |

## 2. Examples: isolation matrix (`case:read`)
**Fixture cases:**

| Case | Grower | Country | Territory | BU | Raised via | Assignee | Shared with |
|---|---|---|---|---|---|---|---|
| A | 70001 (user 1) | IN | 1002 Pune | CP | 501 | 102 | — |
| B | 70002 (user 2) | IN | 1003 Nashik | SEEDS | — | — | — |
| C | 70004 (no user) | IN | 1005 Karnataka | CP | — | — | — |
| D | 70003 (user 3) | BR | 2002 | CP | — | — | — |
| E | 70001 (user 1) | IN | 1002 Pune | SEEDS | — | — | 502 |

**Who sees which cases:**

| Example | Rule | User | Visible |
|---|---|---|---|
| CASE-E01 | R1 | grower.in1 | A, E |
| CASE-E02 | R1 | grower.in2 | B |
| CASE-E03 | R1 | grower.br1 | D |
| CASE-E04 | R4 | care.in (COUNTRY IN) | A, B, C, E |
| CASE-E05 | R4 | care.br | D |
| CASE-E06 | R3 | agro.in (ASSIGNED, Pune, CP) | A |
| CASE-E07 | R4 | tm.in (Maharashtra + districts) | A, B, E |
| CASE-E08 | R2 | retailer.in (501) | A |
| CASE-E09 | R2 | retailer2.in (502) | E; after the share is revoked: none |

**Unit examples (`PolicyEnforcer`, `ScopeFilter`):** country/territory/BU coverage, OWN, ORG, ASSIGNED, expired grant, GLOBAL, missing permission → 404, OR-ed grants with bound parameters, unsafe column fragment rejected.

## 3. Traceability
| Rule / Example | Test |
|---|---|
| E01–E09, R5 | `CaseIsolationIT` (parameterized: SQL rows + policy rows + revoked share) |
| R1–R6 unit | `PolicyEnforcerTest`, `ScopeFilterTest` (parameterized) |
| R7–R10, R12 | `CaseSecurityIT` |
| R11 | `CachingPrincipalResolverIT` |
