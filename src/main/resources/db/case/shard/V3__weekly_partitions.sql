-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V3 · Weekly partitions (doc 05 §6, ADR-004)
-- One partition per ISO week (Monday 00:00 UTC) for every case-keyed table, bounded by ref.id_floor().
-- A DEFAULT partition catches anything outside the created range; it must stay empty (monitored), because a
-- non-empty default partition blocks creating the matching weekly partition later.
-- =====================================================================================================
-- Serialise shard DDL: every shard references the shared ref.* tables, and concurrent FK/partition DDL on
-- them deadlocks. Transaction-scoped, released at commit. Required first statement of every shard migration.
SELECT pg_advisory_xact_lock(7240001);


CREATE TABLE ${shard_schema}.cases_default                   PARTITION OF ${shard_schema}.cases DEFAULT;
CREATE TABLE ${shard_schema}.case_activity_default           PARTITION OF ${shard_schema}.case_activity DEFAULT;
CREATE TABLE ${shard_schema}.case_activity_detail_default    PARTITION OF ${shard_schema}.case_activity_detail DEFAULT;
CREATE TABLE ${shard_schema}.case_attachment_default         PARTITION OF ${shard_schema}.case_attachment DEFAULT;
CREATE TABLE ${shard_schema}.case_comment_default            PARTITION OF ${shard_schema}.case_comment DEFAULT;
CREATE TABLE ${shard_schema}.case_comment_attachment_default PARTITION OF ${shard_schema}.case_comment_attachment DEFAULT;
CREATE TABLE ${shard_schema}.case_share_default              PARTITION OF ${shard_schema}.case_share DEFAULT;

-- Creates missing weekly partitions from weeks_back before to weeks_ahead after the current week.
-- SECURITY DEFINER so the scheduled job can run it as the runtime role without DDL rights of its own.
CREATE FUNCTION ${shard_schema}.ensure_week_partitions(weeks_back integer, weeks_ahead integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    parents CONSTANT text[] := ARRAY['cases', 'case_activity', 'case_activity_detail', 'case_attachment',
                                     'case_comment', 'case_comment_attachment', 'case_share'];
    shard   CONSTANT text   := '${shard_schema}';
    week_start date;
    lo         bigint;
    hi         bigint;
    part       text;
    parent     text;
    created    integer := 0;
BEGIN
    IF weeks_back < 0 OR weeks_ahead < 0 OR weeks_back + weeks_ahead > 520 THEN
        RAISE EXCEPTION 'ensure_week_partitions: invalid range % / %', weeks_back, weeks_ahead USING ERRCODE = '22023';
    END IF;
    FOR w IN -weeks_back .. weeks_ahead LOOP
        week_start := (date_trunc('week', now() AT TIME ZONE 'UTC'))::date + (w * 7);
        CONTINUE WHEN week_start + 7 <= DATE '2026-01-01';          -- entirely before the ID epoch
        lo := greatest(ref.id_floor(week_start::timestamp AT TIME ZONE 'UTC'), 0);
        hi := ref.id_floor((week_start + 7)::timestamp AT TIME ZONE 'UTC');
        FOREACH parent IN ARRAY parents LOOP
            part := parent || '_w' || to_char(week_start, 'YYYYMMDD');
            IF to_regclass(format('%I.%I', shard, part)) IS NULL THEN
                EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES FROM (%s) TO (%s)',
                               shard, part, shard, parent, lo, hi);
                created := created + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN created;
END $$;
REVOKE ALL ON FUNCTION ${shard_schema}.ensure_week_partitions(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${shard_schema}.ensure_week_partitions(integer, integer) TO ${app_role};

-- Cover the previous week (clock skew / late deploys) and the next 8 weeks. A daily job keeps extending it.
SELECT ${shard_schema}.ensure_week_partitions(1, 8);
