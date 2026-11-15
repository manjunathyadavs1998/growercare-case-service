# SPEC-CASE-03: Comments, partner sharing and activity
- **Requirements:** CASE-6, CASE-7, ACC-11, ACC-12 · doc 05 (comments design)
- **Status:** Implemented (agreed 2026-11-15, all example rows green)
- **Owner:** case-service
- **Depends on:** SPEC-CASE-02

## 0. Decisions to agree
| # | Decision | Alternative |
|---|---|---|
| D6 | **Only the grower who owns the case can share it** with a partner (it's their consent, ACC-12). Staff cannot consent on their behalf | Staff record a consent captured offline (later, with evidence) |
| D7 | Posting a comment **never changes the case row or version** (doc 05: no CDC amplification). Comment counts are computed on read for now | A `case_comment_stats` table (later, for feeds) |
| D8 | Comment edit and delete are out of scope for this spec (columns exist) | — |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R50 | caller who can read the case (SPEC-CASE-01) | `POST /cases/{id}/comments {clientMsgId, visibility, body, replyToCommentId?}` | 201 CommentView. The case version is unchanged; outbox `comment.posted` |
| CASE-R51 | — | visibility needs the permission: PUBLIC → `comment:post-public`, INTERNAL → `comment:post-internal`, PARTNER → `comment:post-partner` | otherwise 403 |
| CASE-R52 | same `clientMsgId` on the same case | retry | 200 with the original comment; no duplicate row or event |
| CASE-R53 | case in a closed status | grower or partner posts | 422 `comment.case-closed` (staff may still post) |
| CASE-R54 | `replyToCommentId` | not a comment of this case, or not visible to the caller | 422 `comment.reply-invalid` |
| CASE-R55 | — | body blank after trim, or longer than 4000 · `clientMsgId` not a UUID · unknown visibility | 400 |
| CASE-R56 | caller who can read the case | `GET /cases/{id}/comments?limit&cursor` | Growers see PUBLIC; partners see PUBLIC + PARTNER; staff see all. Newest first, keyset cursor, limit 1–100 |
| CASE-R57 | grower owning the case, with `case:share` | `PUT /cases/{id}/shares/{partnerOrgId}` | 200 share (consentGivenAt = now). Re-sharing a revoked share reactivates it, and repeating is idempotent. Activity SHARED, outbox `case.shared` |
| CASE-R58 | — | partner org unknown, not ACTIVE, or of another country | 422 `share.partner-invalid` |
| CASE-R59 | caller is not the owning grower (staff, partner, other grower in scope) | share or unshare | 403 when they lack `case:share`; 404 when the case is not theirs |
| CASE-R60 | owning grower | `DELETE /cases/{id}/shares/{partnerOrgId}` | 204, share revoked; the partner immediately gets 404 on the case (SPEC-CASE-01 E09). Activity UNSHARED, outbox `case.unshared` |
| CASE-R61 | caller who can read the case | `GET /cases/{id}/activity` | Chronological trail (type, from, to, actor, at). `note` only for STAFF callers |

## 2. Examples
| Example | Rule | Actor | Request | Expected |
|---|---|---|---|---|
| CASE-E60 | R50 | care.in | PUBLIC "We are checking the batch" on case A | 201; case version unchanged; 1 `comment.posted` |
| CASE-E61 | R50 | grower.in1 | PUBLIC on own case A | 201 |
| CASE-E62 | R50 | retailer.in | PARTNER on case A (raised via 501) | 201 |
| CASE-E63 | R51 | grower.in1 | INTERNAL on own case | 403 |
| CASE-E64 | R51 | retailer.in | PUBLIC on case A | 403 |
| CASE-E65 | R51 | care.in | PARTNER | 403 (care lacks `comment:post-partner`) |
| CASE-E66 | R50 | retailer2.in | PARTNER on case A (not shared with 502) | 404 |
| CASE-E67 | R52 | grower.in1 | same `clientMsgId` twice | 201 then 200, the same commentId, 1 row |
| CASE-E68 | R53 | grower.in1 · care.in | post on a CLOSED case | 422 `comment.case-closed` · 201 |
| CASE-E69 | R54 | care.in | reply to a comment of another case | 422 `comment.reply-invalid` |
| CASE-E70 | R55 | care.in | body "   " / 4001 chars / `clientMsgId` "x" / visibility "SECRET" | 400 |
| CASE-E71 | R56 | grower.in1 · retailer.in · care.in | GET comments on case A (PUBLIC + INTERNAL + PARTNER present) | PUBLIC · PUBLIC+PARTNER · all three |
| CASE-E72 | R56 | care.in | 3 comments, `limit=2`, follow the cursor | newest first, no duplicates or skips |
| CASE-E73 | R57 | grower.in1 | share case A with 502 | 200; retailer2.in can GET case A |
| CASE-E74 | R57 | grower.in1 | share again | 200, still one active share |
| CASE-E75 | R58 | grower.in1 | share with 999 · with a BR partner | 422 `share.partner-invalid` |
| CASE-E76 | R59 | care.in · retailer.in | share case A | 403 (no `case:share`) |
| CASE-E77 | R59 | grower.in2 | share case A | 404 |
| CASE-E78 | R60 | grower.in1 | unshare 502 | 204; retailer2.in GET case A → 404 |
| CASE-E79 | R61 | care.in · grower.in1 | GET activity after create + transition with a note | both see CREATED, STATUS_CHANGED; only care.in sees the note |

## 3. API contract
| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/cases/{caseId}/comments` | `{clientMsgId (UUID), visibility, body, replyToCommentId?}` | 201 CommentView · 200 replay | 400, 401, 403, 404, 422 |
| GET | `/cases/{caseId}/comments?limit&cursor` | — | 200 `{items: CommentView[], nextCursor}` | 400, 401, 404 |
| PUT | `/cases/{caseId}/shares/{partnerOrgId}` | — | 200 ShareView | 401, 403, 404, 422 |
| DELETE | `/cases/{caseId}/shares/{partnerOrgId}` | — | 204 | 401, 403, 404 |
| GET | `/cases/{caseId}/activity` | — | 200 ActivityView[] | 401, 404 |

- **CommentView:** `commentId`, `caseId`, `authorUserId`, `authorRole`, `visibility`, `body`, `replyToCommentId`, `createdAt`.
- **ShareView:** `caseId`, `partnerOrgId`, `consentGivenAt`, `revokedAt`.
- **ActivityView:** `activityId`, `type`, `fromStatus`, `toStatus`, `actorUserId`, `note` (staff only), `occurredAt`.

## 4. Data & events
- **Tables:** `<shard>.case_comment` (`INSERT … ON CONFLICT (case_id, client_msg_id) DO NOTHING`), `case_share`, `case_activity`, `outbox`.
- **Events:**
  - `comment.posted` {caseId, commentId, visibility, authorRole} (no body, as it may contain PII);
  - `case.shared` / `case.unshared` {caseId, partnerOrgId}.

## 5. Traceability
| Rule / Example | Test |
|---|---|
| R50 / E60–E62 | `CaseCommentsAndSharingIT$Posting#accepted` (parameterized; case version unchanged, outbox) |
| R51, R55, R50(404) / E63–E66, E70 | `CaseCommentsAndSharingIT$Posting#rejected` (parameterized) |
| R52 / E67 | `…$Posting#caseE67RetryWithSameClientMsgIdIsIdempotent` |
| R53 / E68 | `…$Posting#closedCases` (parameterized) |
| R54 / E69 | `…$Posting#caseE69ReplyMustTargetACommentOfTheSameCase` |
| R56 / E71, E72 | `…$Reading#visibilityFilter` (parameterized), `#caseE72…`, `#invalidLimit` |
| R57, R60 / E73, E74, E78 | `…$Sharing#caseE73E74E78ShareIsIdempotentAndRevocable` |
| R58, R59 / E75–E77 | `…$Sharing#rejected` (parameterized) |
| R61 / E79 | `…$Activity#activityTrail` (parameterized) |
| R51/R56 unit | `CaseRulesTest#eachVisibilityNeedsItsOwnPermission`, `#readableVisibilitiesDependOnUserType` |
