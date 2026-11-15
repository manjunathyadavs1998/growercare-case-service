-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V2 · Comments (doc 05), partner sharing (ACC-12), outbox
-- =====================================================================================================
-- Serialise shard DDL: every shard references the shared ref.* tables, and concurrent FK/partition DDL on
-- them deadlocks. Transaction-scoped, released at commit. Required first statement of every shard migration.
SELECT pg_advisory_xact_lock(7240001);


-- Comments live in the case's shard and partition. Posting never updates the case row (doc 05: no CDC amplification).
CREATE TABLE ${shard_schema}.case_comment (
    case_id             bigint        NOT NULL,
    comment_id          bigint        NOT NULL DEFAULT ${shard_schema}.next_id(),
    author_user_id      bigint        NOT NULL,
    author_role         varchar(40)   NOT NULL CHECK (author_role ~ '^[A-Z][A-Z_]+$'),
    visibility          varchar(8)    NOT NULL CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'PARTNER')),
    body                varchar(4000) NOT NULL CHECK (btrim(body) <> ''),
    lang                varchar(16),
    client_msg_id       uuid          NOT NULL,                -- UUIDv7 from the client: idempotent retries
    reply_to_comment_id bigint,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    edited_at           timestamptz,
    deleted_at          timestamptz,                           -- soft delete; text kept for audit
    CONSTRAINT case_comment_pk PRIMARY KEY (case_id, comment_id),
    CONSTRAINT case_comment_client_msg_uq UNIQUE (case_id, client_msg_id),
    CONSTRAINT case_comment_case_fk FOREIGN KEY (case_id) REFERENCES ${shard_schema}.cases (case_id),
    CONSTRAINT case_comment_reply_fk FOREIGN KEY (case_id, reply_to_comment_id) REFERENCES ${shard_schema}.case_comment (case_id, comment_id),
    CONSTRAINT case_comment_not_reply_to_self CHECK (reply_to_comment_id IS DISTINCT FROM comment_id)
) PARTITION BY RANGE (case_id);
-- Keyset feed: newest first within a case (the PK already serves (case_id, comment_id) range scans).

CREATE TABLE ${shard_schema}.case_comment_attachment (
    case_id       bigint       NOT NULL,
    comment_id    bigint       NOT NULL,
    attachment_no smallint     NOT NULL CHECK (attachment_no BETWEEN 1 AND 10),
    object_key    varchar(512) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 26214400),
    sha256_hex    char(64)     NOT NULL CHECK (sha256_hex ~ '^[0-9a-f]{64}$'),
    CONSTRAINT case_comment_attachment_pk PRIMARY KEY (case_id, comment_id, attachment_no),
    CONSTRAINT case_comment_attachment_fk FOREIGN KEY (case_id, comment_id) REFERENCES ${shard_schema}.case_comment (case_id, comment_id)
) PARTITION BY RANGE (case_id);

-- A case is visible to a partner org only while a share row is active (grower consent, ACC-11/12).
CREATE TABLE ${shard_schema}.case_share (
    case_id            bigint      NOT NULL,
    partner_org_id     bigint      NOT NULL,
    shared_by_user_id  bigint      NOT NULL,
    consent_given_at   timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    revoked_at         timestamptz,
    revoked_by_user_id bigint,
    CONSTRAINT case_share_pk PRIMARY KEY (case_id, partner_org_id),
    CONSTRAINT case_share_case_fk FOREIGN KEY (case_id) REFERENCES ${shard_schema}.cases (case_id),
    CONSTRAINT case_share_partner_fk FOREIGN KEY (partner_org_id) REFERENCES ref.partner_org (partner_org_id),
    CONSTRAINT case_share_revocation_complete CHECK ((revoked_at IS NULL) = (revoked_by_user_id IS NULL))
) PARTITION BY RANGE (case_id);
CREATE INDEX case_share_active_partner_ix ON ${shard_schema}.case_share (partner_org_id, case_id DESC) WHERE revoked_at IS NULL;

-- Outbox (ADR-012): same transaction as the change; Debezium outbox router publishes from every shard schema.
CREATE TABLE ${shard_schema}.outbox (
    event_id       uuid         PRIMARY KEY,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   varchar(64)  NOT NULL,
    event_type     varchar(128) NOT NULL,
    payload        jsonb        NOT NULL,
    occurred_at    timestamptz  NOT NULL DEFAULT now(),
    trace_id       varchar(32)
);
CREATE INDEX outbox_occurred_ix ON ${shard_schema}.outbox (occurred_at);

-- Runtime role: case history is never hard-deleted by the application (purge is a separate, verified job, ADR-004).
REVOKE DELETE, TRUNCATE ON ${shard_schema}.cases, ${shard_schema}.case_activity, ${shard_schema}.case_activity_detail,
    ${shard_schema}.case_attachment, ${shard_schema}.case_comment, ${shard_schema}.case_comment_attachment,
    ${shard_schema}.case_share FROM ${app_role};
