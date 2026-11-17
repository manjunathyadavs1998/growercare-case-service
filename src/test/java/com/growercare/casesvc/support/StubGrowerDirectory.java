package com.growercare.casesvc.support;

import com.growercare.casesvc.application.port.GrowerDirectory;
import com.growercare.common.error.DependencyUnavailableException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for grower-service's {@code /internal/growers/{id}} in tests (SPEC-CASE-06). Starts with the seed growers;
 * tests add growers, make ids fail like an outage, and count calls per id.
 */
public final class StubGrowerDirectory implements GrowerDirectory {

    private final Map<Long, GrowerReplica> growers = new ConcurrentHashMap<>();
    private final Set<Long> failing = ConcurrentHashMap.newKeySet();
    private final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();

    public StubGrowerDirectory() {
        SeedGrowers.replicas().forEach(this::put);
    }

    public void put(GrowerReplica grower) {
        growers.put(grower.growerId(), grower);
    }

    /** Every fetch of {@code growerId} fails like grower-service being down (503 / network). */
    public void failFor(long growerId) {
        failing.add(growerId);
    }

    public int calls(long growerId) {
        var n = calls.get(growerId);
        return n == null ? 0 : n.get();
    }

    @Override
    public Optional<GrowerReplica> fetch(long growerId) {
        calls.computeIfAbsent(growerId, id -> new AtomicInteger()).incrementAndGet();
        if (failing.contains(growerId)) {
            throw new DependencyUnavailableException("grower-service", null);
        }
        return Optional.ofNullable(growers.get(growerId));
    }
}
