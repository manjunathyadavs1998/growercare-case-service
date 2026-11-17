-- Grower replica writes (SPEC-CASE-06 R130/R131). Statement ids: case-grower-replica.<name>

-- D3: a row is only replaced by a higher version, so a slow, older fetch never overwrites a newer one.
-- name: upsert
INSERT INTO ref.grower (grower_id, country_code, territory_id, user_id, display_name, phone_hmac, phone_last4,
                        preferred_partner_org_id, status, version, updated_at)
VALUES (:growerId, :countryCode, :territoryId, :userId, :displayName, :phoneHmac, :phoneLast4,
        :preferredPartnerOrgId, :status, :version, now())
ON CONFLICT (grower_id) DO UPDATE
   SET country_code = EXCLUDED.country_code, territory_id = EXCLUDED.territory_id, user_id = EXCLUDED.user_id,
       display_name = EXCLUDED.display_name, phone_hmac = EXCLUDED.phone_hmac, phone_last4 = EXCLUDED.phone_last4,
       preferred_partner_org_id = EXCLUDED.preferred_partner_org_id, status = EXCLUDED.status,
       version = EXCLUDED.version, updated_at = now()
 WHERE ref.grower.version < EXCLUDED.version
