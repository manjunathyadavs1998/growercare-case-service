-- case-service shard statements (ADR-013). {shard} = validated shard schema; {scope} = ScopeFilter predicate.
-- Statement ids: case.<name>

-- name: findById
SELECT c.case_id, c.case_no, c.country_code, c.case_type_code, c.status_code, c.territory_id, c.bu_code, c.grower_id,
       g.user_id AS grower_user_id, g.display_name AS grower_name, g.phone_last4 AS grower_phone_last4, c.farm_id,
       c.raised_by_user_id, c.raised_channel, c.raised_via_partner_org_id, c.product_id, c.batch_no, c.title, c.description,
       c.priority, c.assignee_user_id, c.sla_due_at, c.created_at, c.updated_at, c.closed_at, c.version,
       ARRAY(SELECT s.partner_org_id FROM {shard}.case_share s WHERE s.case_id = c.case_id AND s.revoked_at IS NULL) AS active_shares
  FROM {shard}.cases c
  JOIN ref.grower g ON g.grower_id = c.grower_id
 WHERE c.case_id = :caseId

-- name: findByCaseNo
SELECT c.case_id, c.case_no, c.country_code, c.case_type_code, c.status_code, c.territory_id, c.bu_code, c.grower_id,
       g.user_id AS grower_user_id, g.display_name AS grower_name, g.phone_last4 AS grower_phone_last4, c.farm_id,
       c.raised_by_user_id, c.raised_channel, c.raised_via_partner_org_id, c.product_id, c.batch_no, c.title, c.description,
       c.priority, c.assignee_user_id, c.sla_due_at, c.created_at, c.updated_at, c.closed_at, c.version,
       ARRAY(SELECT s.partner_org_id FROM {shard}.case_share s WHERE s.case_id = c.case_id AND s.revoked_at IS NULL) AS active_shares
  FROM {shard}.cases c
  JOIN ref.grower g ON g.grower_id = c.grower_id
 WHERE c.case_no = :caseNo

-- Keyset page of one shard, newest first. Hot window: open or closed within 90 days (ADR-003).
-- Optional filters use typed NULL checks so the statement stays static.
-- name: list
SELECT c.case_id, c.case_no, c.country_code, c.case_type_code, c.status_code, c.territory_id, c.bu_code, c.grower_id,
       g.user_id AS grower_user_id, g.display_name AS grower_name, g.phone_last4 AS grower_phone_last4, c.farm_id,
       c.raised_by_user_id, c.raised_channel, c.raised_via_partner_org_id, c.product_id, c.batch_no, c.title, c.description,
       c.priority, c.assignee_user_id, c.sla_due_at, c.created_at, c.updated_at, c.closed_at, c.version,
       ARRAY(SELECT s.partner_org_id FROM {shard}.case_share s WHERE s.case_id = c.case_id AND s.revoked_at IS NULL) AS active_shares
  FROM {shard}.cases c
  JOIN ref.grower g ON g.grower_id = c.grower_id
 WHERE {scope}
   AND (c.closed_at IS NULL OR c.closed_at > now() - interval '90 days')
   AND c.case_id < :beforeId
   AND c.case_id >= :fromId
   AND (CAST(:status AS varchar) IS NULL OR c.status_code = CAST(:status AS varchar))
   AND (CAST(:caseType AS varchar) IS NULL OR c.case_type_code = CAST(:caseType AS varchar))
   AND (CAST(:territoryId AS bigint) IS NULL OR c.territory_id = CAST(:territoryId AS bigint))
   AND (CAST(:assignee AS bigint) IS NULL OR c.assignee_user_id = CAST(:assignee AS bigint))
 ORDER BY c.case_id DESC
 LIMIT :limit

-- name: insert
INSERT INTO {shard}.cases (case_type_code, status_code, territory_id, bu_code, grower_id, farm_id, raised_by_user_id,
                           raised_channel, raised_via_partner_org_id, product_id, batch_no, title, description, priority, sla_due_at)
VALUES (:caseType, :status, :territoryId, :buCode, :growerId, :farmId, :raisedBy,
        :channel, :partnerOrgId, :productId, :batchNo, :title, :description, :priority, :slaDueAt)
RETURNING case_id

-- name: findIdempotency
SELECT i.case_id, i.requested_by_user_id, i.request_hash
  FROM {shard}.case_idempotency i
 WHERE i.client_request_id = :key

-- name: insertIdempotency
INSERT INTO {shard}.case_idempotency (client_request_id, requested_by_user_id, request_hash, case_id)
VALUES (:key, :userId, :hash, :caseId)
ON CONFLICT (client_request_id) DO NOTHING
RETURNING client_request_id

-- Optimistic lock: 0 rows ⇒ stale version. Entering a closed status sets closed_at, any other status clears it.
-- name: updateStatus
UPDATE {shard}.cases
   SET status_code = :toStatus,
       closed_at   = CASE WHEN :closing THEN now() ELSE NULL END,
       version     = version + 1,
       updated_at  = now()
 WHERE case_id = :caseId AND version = :expectedVersion
RETURNING version

-- name: updateAssignee
UPDATE {shard}.cases
   SET assignee_user_id = :assignee, version = version + 1, updated_at = now()
 WHERE case_id = :caseId AND version = :expectedVersion
RETURNING version

-- name: insertActivity
INSERT INTO {shard}.case_activity (case_id, activity_type, actor_user_id, from_status, to_status, note)
VALUES (:caseId, :type, :actor, :fromStatus, :toStatus, :note)
RETURNING activity_id

-- name: insertActivityDetail
INSERT INTO {shard}.case_activity_detail (case_id, activity_id, field_name, old_value, new_value)
VALUES (:caseId, :activityId, :field, :oldValue, :newValue)

-- name: listActivity
SELECT a.activity_id, a.activity_type, a.from_status, a.to_status, a.actor_user_id, a.note, a.occurred_at
  FROM {shard}.case_activity a
 WHERE a.case_id = :caseId
 ORDER BY a.activity_id
