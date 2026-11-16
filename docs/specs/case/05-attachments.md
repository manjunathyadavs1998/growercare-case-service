# SPEC-CASE-05: Case evidence and comment attachments (presigned upload, malware scan, safe download)
- **Requirements:** CASE-3 (rich evidence: photos, pack photo, invoice, prescription, lab report), CMT-10 (attachments via presigned upload + scan before display), PRV-5 (no PII in URLs/logs; precise geolocation is PII), ACC-3/ACC-12 (scope, sharing)
- **Status:** Implemented (agreed 2026-11-16; local smoke CASE-S12a–c green)
- **Owner:** case-service (`attachments` module; extraction to media-service later, ADR-016)
- **Depends on:** SPEC-CASE-01 (scope, 404), SPEC-CASE-03 (comment visibility), SPEC-PLAT-04 (outbox events), SPEC-CASE-04 R95 (per-shard jobs)

## 0. Decisions to agree
| # | Decision | Alternative (not chosen) |
|---|---|---|
| D1 | **A module inside case-service** (user decision). It lives in its own packages and ports (`ObjectStore`, `MalwareScanner`, `AttachmentRepository`), so it can move to media-service unchanged. **ADR-016** records the deviation from `01_SERVICES/media-service.md` and the extraction trigger: media volume, or a second consumer of files | A new media-service now: a third JVM and a third database; the laptop is RAM-bound |
| D2 | **S3 API everywhere.** AWS SDK v2 (`s3` + presigner). Locally **SeaweedFS 3.97** (`chrislusf/seaweedfs`, Apache-2.0) in S3 mode, with an identities file so signatures are enforced. MinIO no longer publishes community images (Oct 2025), and S3Mock doesn't check signatures. Testcontainers runs the same image. Buckets are created by the service only when `gc.attachments.create-buckets=true` (local and tests); in production gc-infra creates them. Three buckets per cell: **staging** (client uploads), **clean** (served), **quarantine** (infected or rejected; retention set by bucket lifecycle in gc-infra). Object key `<shard>/<caseId>/<attachmentId>`: **no file names and no PII in keys or URLs** (PRV-5) | Upload through our API: pods carry every byte |
| D3 | **Two-step upload.** `POST …/attachments` validates the request and returns a **presigned PUT** valid for 5 minutes. The signature covers `Content-Type` and `Content-Length` (AWS also enforces `x-amz-checksum-sha256`). Then `POST …/complete` checks the object exists with the declared **size** (HEAD) and queues the scan. **The scan job recomputes SHA-256 itself** (R105), so integrity doesn't depend on store features | Trusting the client's "done" without checking the object |
| D4 | **Scan job = per-shard poller** (the SLA-timer pattern): it claims `PENDING` rows **one at a time, each in its own transaction**, with `FOR UPDATE SKIP LOCKED`, per-shard error isolation (R95) and per-attachment isolation (R114). Steps: <br>1. download from staging and recompute SHA-256; <br>2. **scan first**, so malware is INFECTED whatever type it claims; <br>3. check the **magic bytes** match the declared type; <br>4. strip image metadata; <br>5. copy to clean, or move to quarantine; <br>6. update the row and write activity + outbox, in one transaction per attachment. <br>Production swaps the trigger for S3 events → SQS workers (media-service doc) | Scanning inside the complete request (slow, and ties up request threads) |
| D5 | **Malware scanner port.** Locally `EicarScanner` flags the standard EICAR test file (user decision). Production uses ClamAV workers or GuardDuty Malware Protection for S3, chosen at the AWS stage | ClamAV container locally (about 1–1.5 GB RAM) |
| D6 | **Image metadata is stripped** before an image reaches the clean bucket (PRV-5: EXIF GPS = precise location). JPEG: drop APP1 (Exif/XMP) and APP13 (IPTC) segments. PNG: drop `eXIf`, `tEXt`, `iTXt`, `zTXt` chunks. Pixels are unchanged. The `latitude`/`longitude` columns stay NULL in v1; capturing geotags needs field-level encryption (PRV-5), a later spec | Keeping the originals: anyone who can download the file can read the grower's farm location |
| D7 | **Allowed types and limits** (ref data, so a country profile can override later, convention 4): <br>• PHOTO / PACK_PHOTO: `image/jpeg`, `image/png`, ≤ 25 MB; <br>• VIDEO: `video/mp4`, ≤ 100 MB (no metadata stripping in v1); <br>• INVOICE / PRESCRIPTION / LAB_REPORT / OTHER: `application/pdf`, `image/jpeg`, `image/png`, ≤ 25 MB. <br>At most **20 live attachments per case** (AWAITING_UPLOAD, PENDING, CLEAN) | Free-form types (an executable named `.jpg`) |
| D8 | **New permission `case:attach`** (IAM V6). It goes to every role that may post PUBLIC or PARTNER comments (grower, partner users, staff with comment rights). Object scope = the caller can read the case (else 404). Growers and partners cannot attach to a closed case (as comments, SPEC-CASE-03 R53) | Reusing comment permissions (blurs audit and future rules) |
| D9 | **Comment attachments reuse case attachments.** `POST …/comments` takes `attachmentIds` (≤ 10): attachments **the caller uploaded on the same case** that are not yet linked and not INFECTED, REJECTED or EXPIRED. Linking sets `case_attachment.comment_id`, so the attachment inherits the comment's visibility (SPEC-CASE-03 R56). The old `case_comment_attachment` table is deprecated (contract step later) | A second upload path for comments |
| D10 | **Who sees what.** A case-level attachment is visible to anyone who can read the case; a comment attachment, to anyone who can see the comment. A CLEAN file can be downloaded through a **60-second presigned GET** returned as JSON (not a redirect, so SPAs can handle it). Non-CLEAN attachments are listed **only to the uploader and staff**, with status and no URL | Public bucket URLs |
| D11 | **Lifecycle.** An AWAITING_UPLOAD row older than 24 h becomes EXPIRED and its staging object (if any) is deleted (daily job, per shard). Clean objects follow the case's archive lifecycle (ADR-003, later) | Leaving orphaned uploads in staging |

## 1. Behaviour (rules)
Status machine: `AWAITING_UPLOAD → PENDING → CLEAN | INFECTED | REJECTED`, and `AWAITING_UPLOAD → EXPIRED`.

| Rule | Given | When | Then |
|---|---|---|---|
| CASE-R96 | caller with `case:attach` who can read the case | `POST /cases/{id}/attachments {kind, contentType, sizeBytes, sha256}` | 201 `{attachmentId, status: AWAITING_UPLOAD, upload: {method: PUT, url, headers, expiresAt}}`; row created; the URL expires in 5 min and its signature binds content type, length and SHA-256 |
| CASE-R97 | — | kind unknown · content type not allowed for the kind · size ≤ 0 or above the kind's limit · sha256 not 64 hex | 400 (field errors) |
| CASE-R98 | caller lacks `case:attach` · cannot read the case | request | 403 · 404 |
| CASE-R99 | the case already has 20 live attachments · the case is closed and the caller is a grower or partner | request | 422 `attachment.limit-reached` · 422 `attachment.case-closed`. The count and insert are **serialised per case** (transaction-scoped advisory lock), so concurrent requests can't exceed 20 |
| CASE-R100 | the uploader, after PUT | `POST /cases/{id}/attachments/{attId}/complete` | object exists with the declared size and checksum → 200, status PENDING. Repeating it once PENDING or later returns 200 with the current state (idempotent) |
| CASE-R101 | the object is missing · its size differs from the declared size | complete | 422 `attachment.upload-missing` · 422 `attachment.upload-mismatch`; the row stays AWAITING_UPLOAD |
| CASE-R102 | somebody other than the uploader | complete | 404 (only the uploader completes) |
| CASE-R103 | a PENDING attachment | the scan job runs | magic bytes match and the scan is clean → **CLEAN**, object copied (metadata-stripped for images) to clean, staging object deleted. Activity `ATTACHMENT_ADDED`; outbox `case.attachment.scanned {caseId, attachmentId, kind, result: CLEAN}` |
| CASE-R104 | PENDING, and the scanner flags it (EICAR) | scan job | **INFECTED**, object moved to quarantine, nothing in clean; event `result: INFECTED`; ERROR log without PII |
| CASE-R105 | PENDING, and the SHA-256 of the stored bytes ≠ the declared one, **or** the magic bytes don't match the declared type (e.g. a PDF sent as `image/png`), or an image can't be parsed | scan job | **REJECTED**, moved to quarantine; event `result: REJECTED` |
| CASE-R106 | a CLEAN JPEG/PNG uploaded with EXIF GPS / text chunks | after scan | the clean object has **no** APP1/APP13 segments (JPEG) or `eXIf`/`tEXt`/`iTXt`/`zTXt` chunks (PNG); image data unchanged |
| CASE-R107 | the same PENDING attachment | two scan jobs run concurrently | processed once (one event, one activity row) |
| CASE-R108 | caller who can read the case | `GET /cases/{id}/attachments` | the attachments visible to them (D10), oldest first, with `status`; non-CLEAN rows only for the uploader and staff |
| CASE-R109 | a visible CLEAN attachment | `GET /cases/{id}/attachments/{attId}/download` | 200 `{url, expiresAt}` (presigned GET on the clean bucket, 60 s, `Content-Disposition: attachment`) |
| CASE-R110 | the attachment is not CLEAN · not visible to the caller | download | 409 `NOT_READY` (rule `attachment.not-available`) · 404 |
| CASE-R111 | the caller's own attachments on the case (not linked, not INFECTED/REJECTED/EXPIRED) | `POST /cases/{id}/comments {…, attachmentIds}` | the comment is created and the attachments linked in the same transaction; CommentView lists them; `comment.posted` carries `attachmentIds` |
| CASE-R112 | an attachment id belongs to another user or case, is already linked, is unusable, or there are more than 10 | post comment | 422 `comment.attachment-invalid` (nothing is created) |
| CASE-R114 | a PENDING attachment whose processing throws (staging object gone, storage error, unexpected parser failure) | scan job | **only that attachment** is rolled back and skipped for this run (ERROR log, ids only); the other attachments of the shard are still processed in the same run. Its `scan_attempts` is incremented; after **3** failed attempts it becomes **REJECTED** (event `REJECTED`), so a poison file can't block the queue |
| CASE-R113 | AWAITING_UPLOAD rows older than 24 h | the expiry job runs | EXPIRED, staging object deleted if present; newer rows unchanged; per shard with R95 isolation |

## 2. Examples (each row = one parameterized test row)
Fixtures: case A (grower.in1, IN, shared with retailer 501). `jpeg` = a small real JPEG with an EXIF GPS block; `eicar` = the EICAR test string declared as `application/pdf`.

| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| CASE-E103 | R96 | grower.in1 · care.in · retailer.in | PHOTO `image/jpeg` 2 MB on case A | 201, AWAITING_UPLOAD, PUT URL, expiresAt ≈ now + 5 min |
| CASE-E104 | R97 | grower.in1 | kind `SELFIE` · PHOTO `application/pdf` · PHOTO 26 MB · VIDEO 101 MB · sha256 `xyz` | 400 each |
| CASE-E105 | R98 | agro.in (no `case:attach`? see note) · care.br | on case A | 403 · 404 |
| CASE-E106 | R99 | grower.in1 | 21st live attachment · on a CLOSED case | 422 `attachment.limit-reached` · 422 `attachment.case-closed` |
| CASE-E106c | R99 | grower.in1 | 25 concurrent requests on a case with no attachments | exactly 20 × 201 and 5 × 422 `attachment.limit-reached`; 20 rows |
| CASE-E107 | R99 | care.in | on a CLOSED case | 201 (staff may still attach) |
| CASE-E108 | R100 | grower.in1 | PUT the declared bytes, complete, complete again | 200 PENDING · 200 PENDING (same row) |
| CASE-E109 | R101 | grower.in1 | complete without PUT · object of a different size (written with the owner's S3 credentials; the presigned PUT signs Content-Length) | 422 `attachment.upload-missing` · 422 `attachment.upload-mismatch` |
| CASE-E110 | R102 | grower.in2 · care.in | complete grower.in1's attachment | 404 · 404 |
| CASE-E111 | R103, R106 | grower.in1 | `jpeg` with GPS → scan | CLEAN; clean object has no APP1; staging deleted; 1 activity + 1 event `CLEAN` |
| CASE-E112 | R106 | grower.in1 | PNG with `tEXt` + `eXIf` → scan | CLEAN; chunks removed; IHDR/IDAT bytes identical |
| CASE-E113 | R104 | grower.in1 | `eicar` → scan | INFECTED; object in quarantine only; event `INFECTED` |
| CASE-E114 | R105 | grower.in1 | PDF bytes declared `image/png` · same-size bytes with a different SHA-256 | REJECTED; quarantine; event `REJECTED` (both rows) |
| CASE-E115 | R107 | — | two pollers on one PENDING row | one event, one activity |
| CASE-E116 | R108, R110 | grower.in1 · care.in · retailer.in · grower.in2 | list after: a CLEAN case photo, a PENDING upload by grower.in1, an INTERNAL-comment attachment | grower: CLEAN + own PENDING · care: all 3 · retailer: CLEAN only · grower.in2: 404 |
| CASE-E117 | R109 | retailer.in | download the CLEAN photo; fetch the URL | 200 `{url, expiresAt ≈ +60 s}`; the URL returns the stripped bytes |
| CASE-E118 | R110 | grower.in1 · retailer.in | download the PENDING one · download the INTERNAL-comment attachment | 409 `attachment.not-available` · 404 |
| CASE-E119 | R111 | care.in | INTERNAL comment with `attachmentIds` = [own CLEAN photo] | 201; CommentView lists it; grower.in1 can no longer see that attachment (inherits INTERNAL) |
| CASE-E120 | R112 | grower.in1 | `attachmentIds` = [care.in's] · [already linked] · [INFECTED] · 11 ids | 422 `comment.attachment-invalid` each; no comment row |
| CASE-E122 | R114 | — | two PENDING uploads; the first's staging object is deleted behind the service | run 1: the second is CLEAN, the first stays PENDING (attempts = 1); runs 2 and 3: the first becomes REJECTED with one `REJECTED` event |
| CASE-E121 | R113 | — | AWAITING_UPLOAD aged 25 h (object present) and 1 h | 25 h → EXPIRED + staging object deleted; 1 h unchanged |

Note E105: if every IN staff role ends up with `case:attach`, the 403 row uses a token without the permission (`TestJwts` user with an empty grant), like the existing permission tests.

**Unit examples:**
- `AttachmentRulesTest`: the kind × content type × size matrix (D7), with boundaries at exactly 25 MB and 100 MB.
- `ImageMetadataStripperTest`: JPEG/PNG fixtures in and out; corrupt input is REJECTED.
- `EicarScannerTest`.
- `MagicBytesTest`: JPEG `FF D8 FF`, PNG `89 50 4E 47`, PDF `%PDF-`, MP4 `ftyp` at offset 4.

## 3. API changes (contract-first: OpenAPI updated in `contracts/case-service.openapi.json`)
```
POST /cases/{caseId}/attachments                         201 AttachmentUpload · 400 · 403 · 404 · 422
POST /cases/{caseId}/attachments/{attachmentId}/complete  200 AttachmentView   · 404 · 422
GET  /cases/{caseId}/attachments                          200 {items: [AttachmentView]} · 404
GET  /cases/{caseId}/attachments/{attachmentId}/download  200 {url, expiresAt}   · 404 · 409
POST /cases/{caseId}/comments   + "attachmentIds": ["…"]   (optional, ≤ 10)
```
AttachmentView: `{attachmentId, caseId, commentId?, kind, contentType, sizeBytes, status, uploadedByUserId, createdAt, scannedAt?}`. IDs are strings. **No storage keys or bucket names in responses.**

## 4. Data & events
| Object | Change |
|---|---|
| shard V6 | `case_attachment`: <br>• expand the `scan_status` CHECK to {AWAITING_UPLOAD, PENDING, CLEAN, INFECTED, REJECTED, EXPIRED} and default it to AWAITING_UPLOAD; <br>• add `comment_id bigint NULL` (FK to `case_comment`), `scanned_at`, `completed_at`; <br>• partial index on (`created_at`) WHERE status IN (AWAITING_UPLOAD, PENDING) for the jobs; <br>• extend the `case_activity_type` CHECK with `ATTACHMENT_ADDED` |
| common V6 | `ref.attachment_type_rule (kind, content_type, max_bytes)`, seeded per D7 |
| IAM V6 | permission `case:attach`, granted to every role holding `comment:post-public` or `comment:post-partner` |
| events | `case.attachment.scanned` on `case.case.events` (key = caseId); JSON Schema in `contracts/events/`. `comment.posted` gains optional `attachmentIds` (BACKWARD-compatible addition) |

## 5. Security
- Function permission `case:attach` (403); object scope (404); comment visibility inherited (SPEC-CASE-03 R56).
- Presigned URLs carry no PII: the keys are ids. Uploads are signed for the exact type, length and checksum; downloads are short-lived and force `attachment` disposition.
- **The service never serves staging or quarantine objects.** Only CLEAN objects get a GET URL.
- Magic-byte check plus scan before anything reaches clean. Image metadata (GPS) stripped.
- Storage credentials come from the environment (IRSA in AWS); the bucket names are config, never request input.

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R96–R99 / E103–E107 | `CaseAttachmentsIT$Requesting` (`#uploadUrlIsIssued`, `#invalidRequestsAre400`, `#deniedRequests`, `#caseE106a…`, `#closedCases`) |
| R100–R102 / E108–E110 | `CaseAttachmentsIT$Completing` |
| R103–R107 / E111–E115 | `CaseAttachmentsIT$Scanning` · `ImageMetadataStripperTest` · `MagicBytesTest` · `EicarScannerTest` |
| R108–R112 / E116–E120 | `CaseAttachmentsIT$VisibilityAndComments` (`#listIsFilteredPerCaller`, `#caseE117…`, `#notDownloadable`, `#caseE119…`, `#invalidLinksRejectTheWholeComment`) |
| R113 / E121 | `CaseAttachmentsIT$Expiry` |
| R114 / E122 | `CaseAttachmentsIT$Scanning#caseE122OneBrokenAttachmentDoesNotBlockTheShard` |
| D7 / E103–E104 (unit) | `AttachmentRulesTest` |
| D8 | `IamSchemaMigrationIT#everyRoleHasPermissions…` (99 role-permission rows) |
| events | `CaseEventContractIT` row `case.attachment.scanned`, `comment.posted` with `attachmentIds` |
| end to end | `local-dev/smoke.sh` CASE-S12a (presigned PUT + complete), S12b (scheduled scan → CLEAN), S12c (partner download via presigned GET) |
