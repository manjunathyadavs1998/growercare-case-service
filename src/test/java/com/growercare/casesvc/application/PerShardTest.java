package com.growercare.casesvc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.shard.ShardId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-04 R95: a failing shard is logged and skipped; the remaining shards are still processed. */
class PerShardTest {

    private static final List<ShardId> SHARDS = IntStream.range(0, 4).mapToObj(n -> new ShardId("IN", n)).toList();

    static Stream<Arguments> failures() {
        return Stream.of(
                argumentSet("CASE-E102 first shard fails", Set.of(0), 2 + 3 + 4L),
                argumentSet("CASE-E102 middle shard fails", Set.of(2), 1 + 2 + 4L),
                argumentSet("CASE-E102 last shard fails", Set.of(3), 1 + 2 + 3L),
                argumentSet("CASE-E102 every shard fails", Set.of(0, 1, 2, 3), 0L),
                argumentSet("CASE-E102 no shard fails", Set.of(), 1 + 2 + 3 + 4L));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void failingShardIsSkipped(Set<Integer> failing, long expectedTotal) {
        List<ShardId> attempted = new ArrayList<>();

        long total = PerShard.sum("test-job", SHARDS, shard -> {
            attempted.add(shard);
            if (failing.contains(shard.shardNo())) {
                throw new IllegalStateException("boom on " + shard);
            }
            return shard.shardNo() + 1L;     // shard n contributes n+1
        });

        assertThat(attempted).containsExactlyElementsOf(SHARDS);
        assertThat(total).isEqualTo(expectedTotal);
    }
}
