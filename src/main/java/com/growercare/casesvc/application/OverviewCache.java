package com.growercare.casesvc.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SPEC-CASE-07 D5: overview numbers are reused for {@code gc.case.overview-cache-ttl} (30 s) per caller scope key.
 * In-process only; never a system of record. Expired entries are dropped on the next write.
 */
@Component
public class OverviewCache {

    private record Entry(Object value, Instant expiresAt) { }

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public OverviewCache(@Value("${gc.case.overview-cache-ttl:PT30S}") Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> compute) {
        var now = clock.instant();
        var hit = entries.get(key);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return (T) hit.value();
        }
        var value = compute.get();
        entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        if (!ttl.isZero()) {
            entries.put(key, new Entry(value, now.plus(ttl)));
        }
        return value;
    }
}
