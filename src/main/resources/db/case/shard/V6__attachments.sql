-- =====================================================================================================
-- case-service · SHARD TEMPLATE · V6 · attachment lifecycle and comment links (SPEC-CASE-05)
-- Expand-only: wider CHECKs, new nullable columns; existing rows stay valid.
-- =====================================================================================================
-- Serialise shard DDL (required first statement of every shard migration).
SELECT pg_advisory_xact_lock(7240001);

-- Status machine AWAITING_UPLOAD → PENDING → CLEAN | INFECTED | REJECTED, and AWAITING_UPLOAD → EXPIRED.
ALTER TABLE ${shard_schema}.case_attachment DROP CONSTRAINT IF EXISTS case_attachment_scan_status_check;
ALTER TABLE ${shard_schema}.case_attachment ADD CONSTRAINT case_attachment_status
    CHECK (scan_status IN ('AWAITING_UPLOAD', 'PENDING', 'CLEAN', 'INFECTED', 'REJECTED', 'EXPIRED'));
ALTER TABLE ${shard_schema}.case_attachment ALTER COLUMN scan_status SET DEFAULT 'AWAITING_UPLOAD';

-- D9: a comment attachment is a case attachment linked to one comment (inherits the comment's visibility).
ALTER TABLE ${shard_schema}.case_attachment
    ADD COLUMN comment_id   bigint,
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN scanned_at   timestamptz,
    ADD CONSTRAINT case_attachment_comment_fk FOREIGN KEY (case_id, comment_id)
        REFERENCES ${shard_schema}.case_comment (case_id, comment_id);

-- Jobs: the scan poller (PENDING) and the expiry job (AWAITING_UPLOAD) touch only unfinished rows.
CREATE INDEX case_attachment_open_ix ON ${shard_schema}.case_attachment (scan_status, created_at)
    WHERE scan_status IN ('AWAITING_UPLOAD', 'PENDING');

-- R103: activity type for a clean attachment.
ALTER TABLE ${shard_schema}.case_activity DROP CONSTRAINT case_activity_type;
ALTER TABLE ${shard_schema}.case_activity ADD CONSTRAINT case_activity_type CHECK (activity_type IN
    ('CREATED', 'STATUS_CHANGED', 'ASSIGNED', 'UNASSIGNED', 'SHARED', 'UNSHARED', 'PRIORITY_CHANGED', 'DETAILS_UPDATED',
     'EVIDENCE_ADDED', 'ROUTED', 'SLA_WARNING', 'SLA_BREACHED', 'ATTACHMENT_ADDED'));

-- D9: case_comment_attachment (V2) is superseded by case_attachment.comment_id; dropped in a later contract step.
COMMENT ON TABLE ${shard_schema}.case_comment_attachment IS 'DEPRECATED (SPEC-CASE-05 D9): use case_attachment.comment_id';
