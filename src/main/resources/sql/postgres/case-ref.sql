-- Reference data (schema ref, read-only replicas + workflow). Statement ids: case-ref.<name>

-- name: grower
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id
  FROM ref.grower g
 WHERE g.grower_id = :growerId AND g.status = 'ACTIVE'

-- name: growersOfUser
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id
  FROM ref.grower g
 WHERE g.user_id = :userId AND g.status = 'ACTIVE'

-- name: farmOfGrower
SELECT count(*) FROM ref.farm f WHERE f.farm_id = :farmId AND f.grower_id = :growerId

-- name: productBu
SELECT p.bu_code FROM ref.product p WHERE p.product_id = :productId

-- name: productRegistered
SELECT count(*) FROM ref.product_registration r
 WHERE r.country_code = :countryCode AND r.product_id = :productId AND r.status = 'REGISTERED'
   AND (r.valid_until IS NULL OR r.valid_until >= current_date)

-- name: batchOfProduct
SELECT count(*) FROM ref.product_batch b WHERE b.product_id = :productId AND b.batch_no = :batchNo

-- name: caseType
SELECT t.case_type_code, t.default_sla_hours, t.active, s.status_code AS initial_status
  FROM ref.case_type t
  JOIN ref.case_status s ON s.case_type_code = t.case_type_code AND s.is_initial
 WHERE t.case_type_code = :caseType

-- name: transitionPermission
SELECT tr.required_permission, ts.is_closed AS to_closed
  FROM ref.case_transition tr
  JOIN ref.case_status ts ON ts.case_type_code = tr.case_type_code AND ts.status_code = tr.to_status
 WHERE tr.case_type_code = :caseType AND tr.from_status = :fromStatus AND tr.to_status = :toStatus

-- name: partner
SELECT p.partner_org_id, p.country_code, p.status FROM ref.partner_org p WHERE p.partner_org_id = :partnerOrgId

-- name: territoryCountries
SELECT DISTINCT t.country_code FROM ref.territory t WHERE t.territory_id IN (:territoryIds)

-- name: shards
SELECT s.country_code, s.shard_no FROM ref.shard s JOIN ref.country c ON c.country_code = s.country_code WHERE c.active

-- SPEC-CASE-04 ---------------------------------------------------------------------------------------

-- name: slaHours
SELECT p.sla_hours FROM ref.case_sla_policy p WHERE p.country_code = :countryCode AND p.case_type_code = :caseType

-- name: routingRules
SELECT r.country_code, r.priority, r.case_type_code, r.bu_code, r.territory_id, r.queue_code
  FROM ref.routing_rule r
  JOIN ref.country c ON c.country_code = r.country_code
 WHERE c.active

-- A territory and all its ancestors (a territory rule matches descendant territories).
-- name: territoryWithAncestors
WITH RECURSIVE up (territory_id, parent_territory_id) AS (
    SELECT t.territory_id, t.parent_territory_id FROM ref.territory t WHERE t.territory_id = :territoryId
    UNION
    SELECT p.territory_id, p.parent_territory_id FROM up JOIN ref.territory p ON p.territory_id = up.parent_territory_id
)
SELECT territory_id FROM up

-- name: queueExists
SELECT count(*) FROM ref.case_queue q WHERE q.queue_code = :queueCode

-- name: activeCountries
SELECT c.country_code FROM ref.country c WHERE c.active
