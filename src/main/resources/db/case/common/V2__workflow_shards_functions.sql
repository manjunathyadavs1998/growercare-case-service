-- =====================================================================================================
-- case-service · common · V2 · Workflow definition (data-driven state machine), shard map, shared functions
-- =====================================================================================================

-- CASE-1: each case type has its own state machine and SLA. Country-specific steps (compliance packs) will add
-- rows keyed by pack version later; the engine reads only these tables, never hard-coded transitions.
CREATE TABLE ref.case_type (
    case_type_code    varchar(40)  PRIMARY KEY CHECK (case_type_code ~ '^[A-Z][A-Z_]+$'),
    name              varchar(80)  NOT NULL,
    default_sla_hours integer      NOT NULL CHECK (default_sla_hours > 0),
    active            boolean      NOT NULL DEFAULT true
);

CREATE TABLE ref.case_status (
    case_type_code varchar(40) NOT NULL REFERENCES ref.case_type (case_type_code),
    status_code    varchar(40) NOT NULL CHECK (status_code ~ '^[A-Z][A-Z_]+$'),
    is_initial     boolean     NOT NULL DEFAULT false,
    is_closed      boolean     NOT NULL DEFAULT false,      -- entering it sets cases.closed_at
    display_order  smallint    NOT NULL,
    PRIMARY KEY (case_type_code, status_code),
    CONSTRAINT case_status_initial_not_closed CHECK (NOT (is_initial AND is_closed))
);
-- Exactly one initial status per case type (at most one here; "at least one" is checked by a test).
CREATE UNIQUE INDEX case_status_one_initial_uq ON ref.case_status (case_type_code) WHERE is_initial;

CREATE TABLE ref.case_transition (
    case_type_code      varchar(40) NOT NULL,
    from_status         varchar(40) NOT NULL,
    to_status           varchar(40) NOT NULL,
    required_permission varchar(64) NOT NULL CHECK (required_permission ~ '^[a-z]+(-[a-z]+)*:[a-z]+(-[a-z]+)*$'),
    PRIMARY KEY (case_type_code, from_status, to_status),
    CONSTRAINT case_transition_from_fk FOREIGN KEY (case_type_code, from_status) REFERENCES ref.case_status (case_type_code, status_code),
    CONSTRAINT case_transition_to_fk   FOREIGN KEY (case_type_code, to_status)   REFERENCES ref.case_status (case_type_code, status_code),
    CONSTRAINT case_transition_not_self CHECK (from_status <> to_status)
);

-- Shard map: one row per logical shard schema. cluster_key names the physical cluster (one locally).
CREATE TABLE ref.shard (
    country_code char(2)     NOT NULL REFERENCES ref.country (country_code),
    shard_no     smallint    NOT NULL CHECK (shard_no BETWEEN 0 AND 63),
    schema_name  varchar(8)  NOT NULL UNIQUE CHECK (schema_name ~ '^[a-z]{2}_s[0-9]{2}$'),
    cluster_key  varchar(32) NOT NULL DEFAULT 'local',
    PRIMARY KEY (country_code, shard_no),
    CONSTRAINT shard_schema_matches CHECK (schema_name = lower(country_code) || '_s' || lpad(shard_no::text, 2, '0'))
);

-- Damm check digit for case_no (doc 04 §4). Must agree with com.growercare.shard.Damm (parity test).
CREATE FUNCTION ref.damm_check_digit(digits text) RETURNS char(1)
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
    t CONSTANT smallint[] := ARRAY[
        [0,3,1,7,5,9,8,6,4,2], [7,0,9,2,1,5,4,8,6,3], [4,2,0,6,8,7,1,3,5,9], [1,7,5,0,9,8,3,4,2,6],
        [6,1,2,3,0,4,5,9,7,8], [3,6,7,4,2,0,9,5,8,1], [5,8,6,9,7,2,0,1,3,4], [8,9,4,5,3,6,2,0,1,7],
        [9,4,3,8,6,1,7,2,0,5], [2,5,8,1,4,3,6,7,9,0]];
    interim smallint := 0;
BEGIN
    IF digits !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'damm_check_digit: digits only' USING ERRCODE = '22023';
    END IF;
    FOR i IN 1 .. length(digits) LOOP
        interim := t[interim + 1][substr(digits, i, 1)::int + 1];
    END LOOP;
    RETURN interim::text;
END $$;

-- Smallest case_id at a given instant (partition bounds, time-range scans). Must agree with IdCodec.floor().
CREATE FUNCTION ref.id_floor(ts timestamptz) RETURNS bigint
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT ((floor(extract(epoch FROM ts) * 1000))::bigint - 1767225600000) << 22
$$;

-- Runtime role: read-only on every ref table (masters change only through migrations / replication jobs).
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO ${app_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA ref FROM ${app_role};
