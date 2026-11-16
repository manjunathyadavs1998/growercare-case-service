-- Shard maintenance and SLA timers (SPEC-CASE-04). Statement ids: maintenance.<name>

-- Single runner per (job, shard) across replicas; released when the transaction ends.
-- name: tryJobLock
SELECT pg_try_advisory_xact_lock(hashtext(:lockKey))

-- name: ensurePartitions
SELECT {shard}.ensure_week_partitions(1, :weeksAhead)

-- name: deleteExpiredIdempotency
DELETE FROM {shard}.case_idempotency WHERE created_at < :cutoff

-- SPEC-PLAT-04 R55: Debezium reads the WAL, so outbox rows are only kept for debugging/replay (3 days).
-- name: deleteExpiredOutbox
DELETE FROM {shard}.outbox WHERE occurred_at < :cutoff

-- SPEC-PLAT-04 R61: consumer inbox retention (7 days), all consumer groups.
-- name: deleteExpiredInbox
DELETE FROM msg.inbox WHERE processed_at < :cutoff

-- Rows that fell into catch-all partitions (must be 0; otherwise a weekly partition is missing).
-- name: defaultPartitionRows
SELECT (SELECT count(*) FROM {shard}.cases_default)
     + (SELECT count(*) FROM {shard}.case_activity_default)
     + (SELECT count(*) FROM {shard}.case_activity_detail_default)
     + (SELECT count(*) FROM {shard}.case_attachment_default)
     + (SELECT count(*) FROM {shard}.case_comment_default)
     + (SELECT count(*) FROM {shard}.case_comment_attachment_default)
     + (SELECT count(*) FROM {shard}.case_share_default)
     + (SELECT count(*) FROM {shard}.case_timer_default)

-- name: insertTimer
INSERT INTO {shard}.case_timer (case_id, kind, due_at) VALUES (:caseId, :kind, :dueAt)

-- name: cancelTimers
UPDATE {shard}.case_timer SET cancelled_at = now()
 WHERE case_id = :caseId AND fired_at IS NULL AND cancelled_at IS NULL

-- Claim due timers; SKIP LOCKED lets several pollers share the work without double firing (R83).
-- name: claimDueTimers
SELECT t.case_id, t.timer_id, t.kind, c.sla_due_at
  FROM {shard}.case_timer t
  JOIN {shard}.cases c ON c.case_id = t.case_id
 WHERE t.fired_at IS NULL AND t.cancelled_at IS NULL AND t.due_at <= now() AND c.closed_at IS NULL
 ORDER BY t.due_at
 LIMIT :limit
 FOR UPDATE OF t SKIP LOCKED

-- name: markTimerFired
UPDATE {shard}.case_timer SET fired_at = now()
 WHERE case_id = :caseId AND timer_id = :timerId AND fired_at IS NULL AND cancelled_at IS NULL

-- R85: reopen re-arms the SLA window (version is bumped by the status update in the same transaction).
-- name: rearmSla
UPDATE {shard}.cases SET sla_started_at = :startedAt, sla_due_at = :dueAt WHERE case_id = :caseId
