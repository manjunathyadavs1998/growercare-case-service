-- case-service: farm and catalog replicas (SPEC-CASE-08). Statement ids: case-catalog-replica.<name>
-- D3: every upsert is version-guarded, so an older fetch never overwrites a newer row.

-- name: upsertFarm
INSERT INTO ref.farm (farm_id, grower_id, name, area_ha, latitude, longitude, status, version)
VALUES (:farmId, :growerId, :name, :areaHa, :latitude, :longitude, :status, :version)
ON CONFLICT (farm_id) DO UPDATE
   SET name = EXCLUDED.name, area_ha = EXCLUDED.area_ha, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
       status = EXCLUDED.status, version = EXCLUDED.version
 WHERE ref.farm.version < EXCLUDED.version AND ref.farm.grower_id = EXCLUDED.grower_id

-- name: upsertProduct
INSERT INTO ref.product (product_id, product_code, bu_code, name, product_kind, version)
VALUES (:productId, :productCode, :buCode, :name, :kind, :version)
ON CONFLICT (product_id) DO UPDATE
   SET product_code = EXCLUDED.product_code, bu_code = EXCLUDED.bu_code, name = EXCLUDED.name,
       product_kind = EXCLUDED.product_kind, version = EXCLUDED.version
 WHERE ref.product.version < EXCLUDED.version

-- name: upsertRegistration
INSERT INTO ref.product_registration (country_code, product_id, registration_no, status, valid_until, version)
VALUES (:countryCode, :productId, :registrationNo, :status, :validUntil, :version)
ON CONFLICT (country_code, product_id) DO UPDATE
   SET registration_no = EXCLUDED.registration_no, status = EXCLUDED.status, valid_until = EXCLUDED.valid_until,
       version = EXCLUDED.version
 WHERE ref.product_registration.version < EXCLUDED.version

-- name: upsertBatch
INSERT INTO ref.product_batch (product_id, batch_no, mfg_date, expiry_date, plant_code, version)
VALUES (:productId, :batchNo, :mfgDate, :expiryDate, :plantCode, :version)
ON CONFLICT (product_id, batch_no) DO UPDATE
   SET mfg_date = EXCLUDED.mfg_date, expiry_date = EXCLUDED.expiry_date, plant_code = EXCLUDED.plant_code,
       version = EXCLUDED.version
 WHERE ref.product_batch.version < EXCLUDED.version

-- name: farm
SELECT f.grower_id, f.status FROM ref.farm f WHERE f.farm_id = :farmId

-- name: productKnown
SELECT EXISTS (SELECT 1 FROM ref.product p WHERE p.product_id = :productId)

-- name: batchKnown
SELECT EXISTS (SELECT 1 FROM ref.product_batch b WHERE b.product_id = :productId AND b.batch_no = :batchNo)
