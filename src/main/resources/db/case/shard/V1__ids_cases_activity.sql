-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V1 · ID functions, cases, activity, evidence, idempotency
-- Runs once per schema in ref.shard (ShardedFlywayMigrator). Placeholders:
--   ${shard_schema} e.g. in_s27 · ${country_code} IN · ${country_ix} 46 · ${shard_ix} 27 · ${shard_ix2} '27'
--   ${case_no_cc} 71 · ${time_zone} Asia/Kolkata · ${app_role}
-- Large tables are RANGE-partitioned on case_id (= creation week, doc 05 §2); a case and all its child rows
-- share the partition key, so a week can later be verified and dropped as a unit (ADR-004).
-- =====================================================================================================
-- Serialise shard DDL: every shard references the shared ref.* tables, and concurrent FK/partition DDL on
-- them deadlocks. Transaction-scoped, released at commit. Required first statement of every shard migration.
SELECT pg_advisory_xact_lock(7240001);

GRANT USAGE ON SCHEMA ${shard_schema} TO ${app_role};

-- ------------------------------------------------------------------ IDs (doc 04 §3, §4)
CREATE SEQUENCE ${shard_schema}.id_seq;
CREATE SEQUENCE ${shard_schema}.case_no_seq;

CREATE FUNCTION ${shard_schema}.next_id() RETURNS bigint
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    our_epoch  CONSTANT bigint := 1767225600000;   -- 2026-01-01T00:00:00Z — equals IdCodec.EPOCH_MS
    country_ix CONSTANT bigint := ${country_ix};
    shard_ix   CONSTANT bigint := ${shard_ix};
    now_ms     bigint;
    seq        bigint;
BEGIN
    seq    := nextval('${shard_schema}.id_seq') % 1024;
    now_ms := floor(extract(epoch FROM clock_timestamp()) * 1000);
    RETURN ((now_ms - our_epoch) << 22) | (country_ix << 16) | (shard_ix << 10) | seq;
END $$;

CREATE FUNCTION ${shard_schema}.next_case_no() RETURNS char(15)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    day_no int    := (clock_timestamp() AT TIME ZONE '${time_zone}')::date - DATE '2026-01-01';
    seq    bigint := nextval('${shard_schema}.case_no_seq') % 1000000;
    body   text;
BEGIN
    IF day_no < 0 OR day_no > 9999 THEN
        RAISE EXCEPTION 'case_no day number out of range: %', day_no USING ERRCODE = '22003';
    END IF;
    body := '${case_no_cc}' || lpad(day_no::text, 4, '0') || '${shard_ix2}' || lpad(seq::text, 6, '0');
    RETURN body || ref.damm_check_digit(body);
END $$;

-- ------------------------------------------------------------------ cases
CREATE TABLE ${shard_schema}.cases (
    case_id                   bigint        NOT NULL DEFAULT ${shard_schema}.next_id(),
    case_no                   char(15)      NOT NULL DEFAULT ${shard_schema}.next_case_no(),
    country_code              char(2)       NOT NULL DEFAULT '${country_code}',
    case_type_code            varchar(40)   NOT NULL,
    status_code               varchar(40)   NOT NULL,
    territory_id              bigint        NOT NULL,
    bu_code                   varchar(8)    NOT NULL,
    grower_id                 bigint        NOT NULL,
    farm_id                   bigint,
    raised_by_user_id         bigint        NOT NULL,          -- IAM user (no cross-service FK)
    raised_channel            varchar(16)   NOT NULL,
    raised_via_partner_org_id bigint,
    product_id                bigint,
    batch_no                  varchar(32),
    title                     varchar(200)  NOT NULL,
    description               varchar(4000) NOT NULL,
    priority                  varchar(8)    NOT NULL DEFAULT 'NORMAL',
    assignee_user_id          bigint,
    sla_due_at                timestamptz,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    closed_at                 timestamptz,
    version                   integer       NOT NULL DEFAULT 0,
    CONSTRAINT cases_pk PRIMARY KEY (case_id),
    CONSTRAINT cases_case_no_format CHECK (case_no ~ '^[1-9][0-9]{14}$'
        AND substr(case_no, 1, 2) = '${case_no_cc}' AND substr(case_no, 7, 2) = '${shard_ix2}'),
    CONSTRAINT cases_country_is_shard_country CHECK (country_code = '${country_code}'),
    CONSTRAINT cases_channel CHECK (raised_channel IN ('APP', 'WEB', 'WHATSAPP', 'IVR', 'CARE_CALL', 'FIELD', 'RETAILER')),
    CONSTRAINT cases_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT cases_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT cases_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT cases_batch_needs_product CHECK (batch_no IS NULL OR product_id IS NOT NULL),
    CONSTRAINT cases_retailer_channel_has_partner CHECK (raised_channel <> 'RETAILER' OR raised_via_partner_org_id IS NOT NULL),
    CONSTRAINT cases_version_non_negative CHECK (version >= 0),
    CONSTRAINT cases_status_fk    FOREIGN KEY (case_type_code, status_code) REFERENCES ref.case_status (case_type_code, status_code),
    CONSTRAINT cases_territory_fk FOREIGN KEY (territory_id, country_code) REFERENCES ref.territory (territory_id, country_code),
    CONSTRAINT cases_bu_fk        FOREIGN KEY (bu_code) REFERENCES ref.business_unit (bu_code),
    CONSTRAINT cases_grower_fk    FOREIGN KEY (grower_id, country_code) REFERENCES ref.grower (grower_id, country_code),
    CONSTRAINT cases_farm_fk      FOREIGN KEY (farm_id, grower_id) REFERENCES ref.farm (farm_id, grower_id),
    CONSTRAINT cases_partner_fk   FOREIGN KEY (raised_via_partner_org_id, country_code) REFERENCES ref.partner_org (partner_org_id, country_code),
    CONSTRAINT cases_product_fk   FOREIGN KEY (product_id) REFERENCES ref.product (product_id),
    CONSTRAINT cases_batch_fk     FOREIGN KEY (product_id, batch_no) REFERENCES ref.product_batch (product_id, batch_no)
) PARTITION BY RANGE (case_id);

-- case_no is unique by construction (country + shard + wrapping counter + day, doc 04 §4); a partitioned table
-- cannot carry a UNIQUE constraint without the partition key, so the lookup index is non-unique and a
-- monitoring query alerts on duplicates.
CREATE INDEX cases_case_no_ix       ON ${shard_schema}.cases (case_no);
CREATE INDEX cases_grower_ix        ON ${shard_schema}.cases (grower_id, case_id DESC);
CREATE INDEX cases_territory_ix     ON ${shard_schema}.cases (territory_id, case_id DESC);
CREATE INDEX cases_status_ix        ON ${shard_schema}.cases (status_code, case_id DESC);
CREATE INDEX cases_assignee_open_ix ON ${shard_schema}.cases (assignee_user_id, case_id DESC) WHERE closed_at IS NULL;
CREATE INDEX cases_partner_ix       ON ${shard_schema}.cases (raised_via_partner_org_id, case_id DESC) WHERE raised_via_partner_org_id IS NOT NULL;
CREATE INDEX cases_batch_ix         ON ${shard_schema}.cases (product_id, batch_no) WHERE batch_no IS NOT NULL;     -- CASE-9 batch signals
CREATE INDEX cases_closed_ix        ON ${shard_schema}.cases (closed_at) WHERE closed_at IS NOT NULL;               -- hot-window routing / purge

-- ------------------------------------------------------------------ activity / audit trail (CASE-7)
CREATE TABLE ${shard_schema}.case_activity (
    case_id       bigint        NOT NULL,
    activity_id   bigint        NOT NULL DEFAULT ${shard_schema}.next_id(),
    activity_type varchar(24)   NOT NULL,
    actor_user_id bigint        NOT NULL,
    from_status   varchar(40),
    to_status     varchar(40),
    note          varchar(1000),
    occurred_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT case_activity_pk PRIMARY KEY (case_id, activity_id),
    CONSTRAINT case_activity_case_fk FOREIGN KEY (case_id) REFERENCES ${shard_schema}.cases (case_id),
    CONSTRAINT case_activity_type CHECK (activity_type IN
        ('CREATED', 'STATUS_CHANGED', 'ASSIGNED', 'UNASSIGNED', 'SHARED', 'UNSHARED', 'PRIORITY_CHANGED', 'DETAILS_UPDATED', 'EVIDENCE_ADDED')),
    CONSTRAINT case_activity_status_fields CHECK (
        CASE activity_type
            WHEN 'CREATED'        THEN from_status IS NULL AND to_status IS NOT NULL
            WHEN 'STATUS_CHANGED' THEN from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
            ELSE from_status IS NULL AND to_status IS NULL
        END)
) PARTITION BY RANGE (case_id);

-- Field-level changes of one activity (old/new values) as rows, not a JSON blob.
CREATE TABLE ${shard_schema}.case_activity_detail (
    case_id     bigint       NOT NULL,
    activity_id bigint       NOT NULL,
    field_name  varchar(64)  NOT NULL CHECK (field_name ~ '^[a-z][a-z_]*$'),
    old_value   varchar(500),
    new_value   varchar(500),
    CONSTRAINT case_activity_detail_pk PRIMARY KEY (case_id, activity_id, field_name),
    CONSTRAINT case_activity_detail_fk FOREIGN KEY (case_id, activity_id) REFERENCES ${shard_schema}.case_activity (case_id, activity_id)
) PARTITION BY RANGE (case_id);

-- ------------------------------------------------------------------ evidence (CASE-3): metadata only; bytes live in S3
CREATE TABLE ${shard_schema}.case_attachment (
    case_id             bigint       NOT NULL,
    attachment_id       bigint       NOT NULL DEFAULT ${shard_schema}.next_id(),
    kind                varchar(16)  NOT NULL CHECK (kind IN ('PHOTO', 'VIDEO', 'PACK_PHOTO', 'INVOICE', 'PRESCRIPTION', 'LAB_REPORT', 'OTHER')),
    object_key          varchar(512) NOT NULL,
    content_type        varchar(100) NOT NULL,
    size_bytes          bigint       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 104857600),
    sha256_hex          char(64)     NOT NULL CHECK (sha256_hex ~ '^[0-9a-f]{64}$'),
    scan_status         varchar(16)  NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED')),
    captured_at         timestamptz,
    latitude            numeric(9,6) CHECK (latitude BETWEEN -90 AND 90),
    longitude           numeric(9,6) CHECK (longitude BETWEEN -180 AND 180),
    uploaded_by_user_id bigint       NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT case_attachment_pk PRIMARY KEY (case_id, attachment_id),
    CONSTRAINT case_attachment_case_fk FOREIGN KEY (case_id) REFERENCES ${shard_schema}.cases (case_id),
    CONSTRAINT case_attachment_point_complete CHECK ((latitude IS NULL) = (longitude IS NULL))
) PARTITION BY RANGE (case_id);

-- ------------------------------------------------------------------ idempotent create (CASE-8)
-- Not partitioned: the key is the client's UUID, not case_id. Rows expire after 7 days (cleanup job).
CREATE TABLE ${shard_schema}.case_idempotency (
    client_request_id    uuid        PRIMARY KEY,
    requested_by_user_id bigint      NOT NULL,
    request_hash         bytea       NOT NULL CHECK (octet_length(request_hash) = 32),   -- SHA-256 of the canonical request
    case_id              bigint      NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX case_idempotency_created_ix ON ${shard_schema}.case_idempotency (created_at);
