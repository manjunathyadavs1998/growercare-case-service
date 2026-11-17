-- =====================================================================================================
-- case-service · common · V7 · grower replica maintained from grower-service (SPEC-CASE-06 R150, D3, D4) — expand
-- =====================================================================================================

-- D3: grower-service's version, so an older fetch never overwrites a newer row.
ALTER TABLE ref.grower ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE ref.grower ADD CONSTRAINT ref_grower_version_check CHECK (version >= 0);

-- D4: the replica carries grower-service's status, including assisted growers awaiting OTP consent.
ALTER TABLE ref.grower DROP CONSTRAINT grower_status_check;
ALTER TABLE ref.grower ADD CONSTRAINT ref_grower_status_check
    CHECK (status IN ('ACTIVE', 'PENDING_CONSENT', 'INACTIVE', 'ERASED'));

-- R150: the one ref table the runtime role writes (upserts from events and read-through); never deletes.
GRANT INSERT, UPDATE ON ref.grower TO ${app_role};
REVOKE DELETE, TRUNCATE ON ref.grower FROM ${app_role};
