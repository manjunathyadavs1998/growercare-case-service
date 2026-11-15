-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V4 · queue, SLA start, durable SLA timers (SPEC-CASE-04 D2, D5)
-- Expand-only: new nullable columns and a new table; existing rows stay valid.
-- =====================================================================================================
-- Serialise shard DDL: every shard references the shared ref.* tables, and concurrent FK/partition DDL on
-- them deadlocks. Transaction-scoped, released at commit. Required first statement of every shard migration.
SELECT pg_advisory_xact_lock(7240001);

-- ------------------------------------------------------------------ cases: queue + SLA start (reopen re-arms)
ALTER TABLE ${shard_schema}.cases
    ADD COLUMN queue_code     varchar(44),
    ADD COLUMN sla_started_at timestamptz;
ALTER TABLE ${shard_schema}.cases
    ADD CONSTRAINT cases_queue_fk FOREIGN KEY (queue_code, country_code) REFERENCES ref.case_queue (queue_code, country_code);
CREATE INDEX cases_queue_ix ON ${shard_schema}.cases (queue_code, case_id DESC) WHERE queue_code IS NOT NULL;

-- ------------------------------------------------------------------ activity types for SLA + routing
ALTER TABLE ${shard_schema}.case_activity DROP CONSTRAINT case_activity_type;
ALTER TABLE ${shard_schema}.case_activity ADD CONSTRAINT case_activity_type CHECK (activity_type IN
    ('CREATED', 'STATUS_CHANGED', 'ASSIGNED', 'UNASSIGNED', 'SHARED', 'UNSHARED', 'PRIORITY_CHANGED', 'DETAILS_UPDATED',
     'EVIDENCE_ADDED', 'ROUTED', 'SLA_WARNING', 'SLA_BREACHED'));

-- ------------------------------------------------------------------ durable SLA timers (R81–R85)
CREATE TABLE ${shard_schema}.case_timer (
    case_id      bigint      NOT NULL,
    timer_id     bigint      NOT NULL DEFAULT ${shard_schema}.next_id(),
    kind         varchar(8)  NOT NULL CHECK (kind IN ('WARN_50', 'WARN_75', 'WARN_90', 'BREACH')),
    due_at       timestamptz NOT NULL,
    fired_at     timestamptz,
    cancelled_at timestamptz,
    CONSTRAINT case_timer_pk PRIMARY KEY (case_id, timer_id),
    CONSTRAINT case_timer_case_fk FOREIGN KEY (case_id) REFERENCES ${shard_schema}.cases (case_id),
    CONSTRAINT case_timer_one_outcome CHECK (fired_at IS NULL OR cancelled_at IS NULL)
) PARTITION BY RANGE (case_id);
-- The poller's access path: unfired, uncancelled timers by due time.
CREATE INDEX case_timer_due_ix ON ${shard_schema}.case_timer (due_at) WHERE fired_at IS NULL AND cancelled_at IS NULL;
CREATE TABLE ${shard_schema}.case_timer_default PARTITION OF ${shard_schema}.case_timer DEFAULT;

-- ------------------------------------------------------------------ weekly partitions now include case_timer
CREATE OR REPLACE FUNCTION ${shard_schema}.ensure_week_partitions(weeks_back integer, weeks_ahead integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    parents CONSTANT text[] := ARRAY['cases', 'case_activity', 'case_activity_detail', 'case_attachment',
                                     'case_comment', 'case_comment_attachment', 'case_share', 'case_timer'];
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
        CONTINUE WHEN week_start + 7 <= DATE '2026-01-01';
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

-- Create case_timer partitions for every week that already has case partitions (back 1, ahead 8).
SELECT ${shard_schema}.ensure_week_partitions(1, 8);
