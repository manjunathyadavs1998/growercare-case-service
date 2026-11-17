-- =====================================================================================================
-- case-service · common · V8 · farm and catalog replicas maintained from their owners (SPEC-CASE-08 R180) — expand
-- ref.farm from grower-service (SPEC-GRW-02); ref.product, ref.product_registration, ref.product_batch from
-- catalog-registry-service (SPEC-CAT-01). Existing seed rows stay, at version -1 (D6, R182).
-- =====================================================================================================

-- D3: the owner's version, so an older fetch never overwrites a newer row. Rows that exist before this migration (the
-- seed copies, D6) get version -1, so the owner's first answer replaces them even at the owner's version 0 (R182).
ALTER TABLE ref.farm ADD COLUMN version integer NOT NULL DEFAULT -1;
ALTER TABLE ref.product ADD COLUMN version bigint NOT NULL DEFAULT -1;
ALTER TABLE ref.product_registration ADD COLUMN version bigint NOT NULL DEFAULT -1;
ALTER TABLE ref.product_batch ADD COLUMN version bigint NOT NULL DEFAULT -1;
ALTER TABLE ref.farm ADD CONSTRAINT ref_farm_version_check CHECK (version >= -1);

-- D4: archived farms stay (old cases reference them) but take no new cases.
ALTER TABLE ref.farm ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE ref.farm ADD CONSTRAINT ref_farm_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED'));

-- R180: the runtime role upserts these four replicas (events and read-through); never deletes. Every other ref table
-- stays read-only.
GRANT INSERT, UPDATE ON ref.farm, ref.product, ref.product_registration, ref.product_batch TO ${app_role};
REVOKE DELETE, TRUNCATE ON ref.farm, ref.product, ref.product_registration, ref.product_batch FROM ${app_role};
