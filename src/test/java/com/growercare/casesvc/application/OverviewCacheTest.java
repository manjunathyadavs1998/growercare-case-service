package com.growercare.casesvc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-CASE-07 D5 / R214: overview numbers are reused for 30 s per caller scope, then recomputed. */
class OverviewCacheTest {

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            CASE-E214 same scope within the window,  scope-a, 29, 1
            CASE-E214 same scope at the window end,   scope-a, 30, 2
            CASE-E214 another scope,                  scope-b, 1,  2
            """)
    void reusedWithinTheWindowPerScope(String name, String secondKey, int secondsLater, int computations) {
        var clock = new MovableClock();
        var cache = new OverviewCache(Duration.ofSeconds(30), clock);
        var calls = new AtomicInteger();
        cache.get("scope-a", calls::incrementAndGet);
        clock.now = clock.now.plusSeconds(secondsLater);
        cache.get(secondKey, calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(computations);
    }
}
