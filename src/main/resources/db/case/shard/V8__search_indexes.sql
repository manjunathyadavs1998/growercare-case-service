-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V8 · search by product (SPEC-CASE-07 R201)
-- Expand only. Grower search uses cases_grower_ix and batch search cases_batch_ix (both exist since V1).
-- =====================================================================================================
-- Serialise shard DDL (required first statement of every shard migration).
SELECT pg_advisory_xact_lock(7240001);
SET LOCAL lock_timeout = '3s';

CREATE INDEX IF NOT EXISTS cases_product_ix ON ${shard_schema}.cases (product_id, case_id DESC) WHERE product_id IS NOT NULL;
