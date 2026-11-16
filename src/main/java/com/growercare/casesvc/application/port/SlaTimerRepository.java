package com.growercare.casesvc.application.port;

import com.growercare.casesvc.domain.SlaRules.Timer;
import com.growercare.casesvc.domain.SlaRules.TimerKind;
import com.growercare.shard.ShardId;
import java.time.Instant;
import java.util.List;

/** SPEC-CASE-04: durable SLA timers and per-shard maintenance (hot store, Postgres). */
public interface SlaTimerRepository {

    record DueTimer(long caseId, long timerId, TimerKind kind, Instant slaDueAt) { }

    void insertTimers(ShardId shard, long caseId, List<Timer> timers);

    /** R84: cancels every unfired timer of the case. */
    int cancelTimers(ShardId shard, long caseId);

    /** R85: new SLA window after a reopen. */
    void rearmSla(ShardId shard, long caseId, Instant startedAt, Instant dueAt);

    /** R82/R83: claims due timers with FOR UPDATE SKIP LOCKED (must run inside a transaction). */
    List<DueTimer> claimDueTimers(ShardId shard, int limit);

    /** @return false when the timer was already fired or cancelled (exactly-once guard) */
    boolean markFired(ShardId shard, long caseId, long timerId);

    /** R94: transaction-scoped advisory lock; false when another replica holds it. */
    boolean tryJobLock(String job, ShardId shard);

    /** R91: creates missing weekly partitions up to {@code weeksAhead}; returns how many were created. */
    int ensurePartitions(ShardId shard, int weeksAhead);

    /** R92 */
    int deleteExpiredIdempotency(ShardId shard, Instant cutoff);

    /** R93 */
    long defaultPartitionRows(ShardId shard);

    /** SPEC-PLAT-04 R55: deletes outbox rows of one shard that occurred before {@code cutoff}. */
    int deleteExpiredOutbox(ShardId shard, Instant cutoff);

    /** SPEC-PLAT-04 R61: deletes inbox rows (all consumer groups) processed before {@code cutoff}. */
    int deleteExpiredInbox(Instant cutoff);
}
