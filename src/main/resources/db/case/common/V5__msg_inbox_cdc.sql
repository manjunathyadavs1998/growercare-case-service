-- =====================================================================================================
-- case-service · common · V5 · consumer inbox, CDC heartbeat, Debezium outbox publication (SPEC-PLAT-04)
-- =====================================================================================================

CREATE SCHEMA IF NOT EXISTS msg;
GRANT USAGE ON SCHEMA msg TO ${app_role};

-- D5 / R56–R57: idempotent consumers. The row is written in the side effect's transaction; a duplicate delivery
-- finds it and is skipped. 7-day retention (R61, maintenance job).
CREATE TABLE msg.inbox (
    consumer_group varchar(128) NOT NULL,
    event_id       uuid         NOT NULL,
    processed_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX inbox_processed_ix ON msg.inbox (processed_at);

-- R62: Debezium upserts this single row every 10 s so the replication slot advances even without events.
CREATE TABLE msg.cdc_heartbeat (
    id      smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    beat_at timestamptz NOT NULL
);
REVOKE ALL ON msg.cdc_heartbeat FROM ${app_role};

-- R52: every shard migration adds its outbox (shard V5). Insert/update only: housekeeping deletes are not streamed.
CREATE PUBLICATION gc_outbox FOR TABLE msg.cdc_heartbeat WITH (publish = 'insert, update');

-- R53: the connector role may touch the heartbeat only (shard V5 grants SELECT on each outbox).
GRANT USAGE ON SCHEMA msg TO ${cdc_role};
GRANT SELECT, INSERT, UPDATE ON msg.cdc_heartbeat TO ${cdc_role};
