package com.growercare.casesvc.application;

import com.growercare.shard.ShardId;
import java.util.List;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPEC-CASE-04 R95: runs a background job shard by shard with per-shard error isolation. A shard that throws is
 * logged at ERROR and contributes 0; the remaining shards are still processed, and the failed shard is retried on the
 * job's next run. Only {@link RuntimeException}s are caught; {@link Error}s still propagate.
 */
final class PerShard {

    private static final Logger log = LoggerFactory.getLogger(PerShard.class);

    private PerShard() {
    }

    static long sum(String job, List<ShardId> shards, ToLongFunction<ShardId> work) {
        return shards.stream().mapToLong(shard -> {
            try {
                return work.applyAsLong(shard);
            } catch (RuntimeException e) {
                log.error("job {} failed on shard {}; skipped, retried on the next run (SPEC-CASE-04 R95)",
                        job, shard.schemaName(), e);
                return 0L;
            }
        }).sum();
    }
}
