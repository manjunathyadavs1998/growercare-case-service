-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V7 · scan attempts per attachment (SPEC-CASE-05 R114)
-- Expand-only: a new column with a default; existing rows start at 0.
-- =====================================================================================================
-- Serialise shard DDL (required first statement of every shard migration).
SELECT pg_advisory_xact_lock(7240001);

-- R114: failed scan attempts; at 3 the attachment is REJECTED so a poison file cannot block the queue.
ALTER TABLE ${shard_schema}.case_attachment
    ADD COLUMN scan_attempts smallint NOT NULL DEFAULT 0 CHECK (scan_attempts >= 0);
