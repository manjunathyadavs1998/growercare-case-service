-- Attachments (SPEC-CASE-05). Statement ids: attachment.<name>

-- name: typeRules
SELECT kind, content_type, max_bytes FROM ref.attachment_type_rule

-- The object key is derived from the generated id (D2: ids only, no file names).
-- name: insert
WITH id AS (SELECT {shard}.next_id() AS attachment_id)
INSERT INTO {shard}.case_attachment (case_id, attachment_id, kind, object_key, content_type, size_bytes, sha256_hex,
                                     scan_status, uploaded_by_user_id)
SELECT :caseId, id.attachment_id, :kind, :keyPrefix || id.attachment_id, :contentType, :sizeBytes, :sha256Hex,
       'AWAITING_UPLOAD', :uploadedBy
  FROM id
RETURNING case_id, attachment_id, kind, content_type, size_bytes, sha256_hex, scan_status, object_key,
          uploaded_by_user_id, comment_id, created_at, completed_at, scanned_at

-- R99: serialises count-then-insert per case for the rest of the transaction (case ids are globally unique).
-- name: lockCaseAttachments
SELECT pg_advisory_xact_lock(hashtextextended('gc.attachment-limit.' || :caseId, 0))

-- R99
-- name: countLive
SELECT count(*) FROM {shard}.case_attachment
 WHERE case_id = :caseId AND scan_status IN ('AWAITING_UPLOAD', 'PENDING', 'CLEAN')

-- D10: the linked comment's visibility comes along (NULL for case-level attachments).
-- name: find
SELECT a.case_id, a.attachment_id, a.kind, a.content_type, a.size_bytes, a.sha256_hex, a.scan_status, a.object_key,
       a.uploaded_by_user_id, a.comment_id, a.created_at, a.completed_at, a.scanned_at, c.visibility AS comment_visibility
  FROM {shard}.case_attachment a
  LEFT JOIN {shard}.case_comment c ON c.case_id = a.case_id AND c.comment_id = a.comment_id
 WHERE a.case_id = :caseId AND a.attachment_id = :attachmentId

-- name: listByCase
SELECT a.case_id, a.attachment_id, a.kind, a.content_type, a.size_bytes, a.sha256_hex, a.scan_status, a.object_key,
       a.uploaded_by_user_id, a.comment_id, a.created_at, a.completed_at, a.scanned_at, c.visibility AS comment_visibility
  FROM {shard}.case_attachment a
  LEFT JOIN {shard}.case_comment c ON c.case_id = a.case_id AND c.comment_id = a.comment_id
 WHERE a.case_id = :caseId
 ORDER BY a.attachment_id

-- R100
-- name: markPending
UPDATE {shard}.case_attachment SET scan_status = 'PENDING', completed_at = :completedAt
 WHERE case_id = :caseId AND attachment_id = :attachmentId AND scan_status = 'AWAITING_UPLOAD'

-- D4 / R107 / R114: one row per transaction; replicas share the queue (SKIP LOCKED), and rows that already failed
-- in this run are excluded so the rest of the shard is still processed.
-- name: claimNext
SELECT case_id, attachment_id, kind, content_type, size_bytes, sha256_hex, scan_status, object_key,
       uploaded_by_user_id, comment_id, created_at, completed_at, scanned_at
  FROM {shard}.case_attachment
 WHERE scan_status = 'PENDING' AND attachment_id NOT IN (:exclude)
 ORDER BY completed_at NULLS FIRST
 LIMIT 1
 FOR UPDATE SKIP LOCKED

-- R114: counts a failed attempt (separate transaction from the failed one). Returns the new count.
-- name: recordScanFailure
UPDATE {shard}.case_attachment SET scan_attempts = scan_attempts + 1
 WHERE case_id = :caseId AND attachment_id = :attachmentId AND scan_status = 'PENDING'
RETURNING scan_attempts

-- name: markScanned
UPDATE {shard}.case_attachment SET scan_status = :result, scanned_at = :scannedAt
 WHERE case_id = :caseId AND attachment_id = :attachmentId AND scan_status = 'PENDING'

-- R111: only the uploader's own live, unlinked attachments on this case.
-- name: linkToComment
UPDATE {shard}.case_attachment SET comment_id = :commentId
 WHERE case_id = :caseId AND attachment_id IN (:attachmentIds)
   AND uploaded_by_user_id = :uploadedBy AND comment_id IS NULL
   AND scan_status IN ('AWAITING_UPLOAD', 'PENDING', 'CLEAN')

-- name: idsByComment
SELECT comment_id, attachment_id FROM {shard}.case_attachment
 WHERE case_id = :caseId AND comment_id IN (:commentIds)
 ORDER BY attachment_id

-- R113
-- name: expireStale
UPDATE {shard}.case_attachment SET scan_status = 'EXPIRED'
 WHERE scan_status = 'AWAITING_UPLOAD' AND created_at < :cutoff
RETURNING case_id, attachment_id, kind, content_type, size_bytes, sha256_hex, scan_status, object_key,
          uploaded_by_user_id, comment_id, created_at, completed_at, scanned_at
