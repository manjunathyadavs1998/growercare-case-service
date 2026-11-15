-- Comments and partner shares (SPEC-CASE-03). Statement ids: comment.<name>

-- Idempotent on (case_id, client_msg_id): a retry inserts nothing and returns no row.
-- name: insert
INSERT INTO {shard}.case_comment (case_id, author_user_id, author_role, visibility, body, client_msg_id, reply_to_comment_id)
VALUES (:caseId, :authorUserId, :authorRole, :visibility, :body, :clientMsgId, :replyTo)
ON CONFLICT (case_id, client_msg_id) DO NOTHING
RETURNING case_id, comment_id, author_user_id, author_role, visibility, body, reply_to_comment_id, created_at

-- name: findByClientMsgId
SELECT case_id, comment_id, author_user_id, author_role, visibility, body, reply_to_comment_id, created_at
  FROM {shard}.case_comment
 WHERE case_id = :caseId AND client_msg_id = :clientMsgId

-- name: findById
SELECT case_id, comment_id, author_user_id, author_role, visibility, body, reply_to_comment_id, created_at
  FROM {shard}.case_comment
 WHERE case_id = :caseId AND comment_id = :commentId AND deleted_at IS NULL

-- name: list
SELECT case_id, comment_id, author_user_id, author_role, visibility, body, reply_to_comment_id, created_at
  FROM {shard}.case_comment
 WHERE case_id = :caseId
   AND visibility IN (:visibilities)
   AND deleted_at IS NULL
   AND comment_id < :beforeId
 ORDER BY comment_id DESC
 LIMIT :limit

-- name: findShare
SELECT case_id, partner_org_id, consent_given_at, revoked_at
  FROM {shard}.case_share
 WHERE case_id = :caseId AND partner_org_id = :partnerOrgId

-- Share or re-activate a revoked share (consent time renewed only when it was not active).
-- name: upsertShare
INSERT INTO {shard}.case_share AS s (case_id, partner_org_id, shared_by_user_id, consent_given_at)
VALUES (:caseId, :partnerOrgId, :sharedBy, now())
ON CONFLICT (case_id, partner_org_id) DO UPDATE
   SET consent_given_at   = CASE WHEN s.revoked_at IS NULL THEN s.consent_given_at ELSE now() END,
       shared_by_user_id  = CASE WHEN s.revoked_at IS NULL THEN s.shared_by_user_id ELSE EXCLUDED.shared_by_user_id END,
       revoked_at         = NULL,
       revoked_by_user_id = NULL
RETURNING case_id, partner_org_id, consent_given_at, revoked_at

-- name: revokeShare
UPDATE {shard}.case_share
   SET revoked_at = now(), revoked_by_user_id = :revokedBy
 WHERE case_id = :caseId AND partner_org_id = :partnerOrgId AND revoked_at IS NULL
RETURNING case_id
