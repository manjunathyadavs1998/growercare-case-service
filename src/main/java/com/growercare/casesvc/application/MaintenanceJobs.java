package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.application.port.SlaTimerRepository;
import com.growercare.shard.ShardId;
import java.time.Clock;
import java.time.Duration;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-CASE-04 R91–R94: per-shard maintenance. Every shard runs in its own transaction under a transaction-scoped
 * advisory try-lock, so concurrent replicas never process the same shard; a shard that is locked elsewhere is
 * skipped (and counted as 0).
 */
@Service
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final ReferenceData ref;
    private final SlaTimerRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MaintenanceJobs(ReferenceData ref, SlaTimerRepository repo, TransactionTemplate tx, Clock clock) {
        this.ref = ref;
        this.repo = repo;
        this.tx = tx;
        this.clock = clock;
    }

    /** R91: weekly partitions up to {@code weeksAhead}; returns partitions created. */
    public int ensurePartitions(int weeksAhead) {
        return (int) perShard("partitions", shard -> repo.ensurePartitions(shard, weeksAhead));
    }

    /** R92: deletes idempotency keys older than {@code ttl}; returns rows deleted. */
    public int cleanupIdempotency(Duration ttl) {
        var cutoff = clock.instant().minus(ttl);
        return (int) perShard("idempotency", shard -> repo.deleteExpiredIdempotency(shard, cutoff));
    }

    /** R93: total rows found in catch-all partitions (must be 0). Shards with rows are logged at ERROR. */
    public long defaultPartitionRows() {
        return perShard("default-check", shard -> {
            long rows = repo.defaultPartitionRows(shard);
            if (rows > 0) {
                log.error("{} row(s) in default partitions of {}: a weekly partition is missing (SPEC-CASE-04 R93)",
                        rows, shard.schemaName());
            }
            return rows;
        });
    }

    private long perShard(String job, ToLongFunction<ShardId> work) {
        return ref.allShards().stream().mapToLong(shard -> {
            Long result = tx.execute(status -> repo.tryJobLock(job, shard) ? work.applyAsLong(shard) : 0L);
            return result == null ? 0L : result;
        }).sum();
    }
}
