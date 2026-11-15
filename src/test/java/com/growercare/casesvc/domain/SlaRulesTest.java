package com.growercare.casesvc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.domain.SlaRules.SlaState;
import com.growercare.casesvc.domain.SlaRules.TimerKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-04 R80, R81, R86 (SLA hours, timer arithmetic, computed SLA state). */
class SlaRulesTest {

    static final Instant START = Instant.parse("2026-11-16T00:00:00Z");
    static final Instant DUE = START.plus(Duration.ofHours(100));

    @ParameterizedTest(name = "CASE-R81 {0} fires at {1}% of the SLA")
    @CsvSource(textBlock = """
            WARN_50, 50, 2026-11-18T02:00:00Z
            WARN_75, 75, 2026-11-19T03:00:00Z
            WARN_90, 90, 2026-11-19T18:00:00Z
            BREACH, 100, 2026-11-20T04:00:00Z
            """)
    void timersFireAtTheirFractionOfTheSla(TimerKind kind, int percent, Instant expectedDue) {
        assertThat(kind.percent()).isEqualTo(percent);
        assertThat(SlaRules.timers(START, DUE))
                .filteredOn(t -> t.kind() == kind)
                .singleElement()
                .satisfies(t -> assertThat(t.dueAt()).isEqualTo(expectedDue));
        assertThat(SlaRules.timers(START, DUE)).hasSize(4);
    }

    static Stream<Arguments> states() {
        return Stream.of(
                argumentSet("CASE-E95a open, 10% elapsed", START.plus(Duration.ofHours(10)), null, SlaState.ON_TRACK),
                argumentSet("CASE-E95b open, 74.99% elapsed", START.plus(Duration.ofMinutes(75 * 60 - 1)), null, SlaState.ON_TRACK),
                argumentSet("CASE-E95c open, exactly 75% elapsed", START.plus(Duration.ofHours(75)), null, SlaState.AT_RISK),
                argumentSet("CASE-E95d open, 80% elapsed", START.plus(Duration.ofHours(80)), null, SlaState.AT_RISK),
                argumentSet("CASE-E95e open, exactly due", DUE, null, SlaState.BREACHED),
                argumentSet("CASE-E95f open, past due", DUE.plusSeconds(1), null, SlaState.BREACHED),
                argumentSet("CASE-E95g closed before due", DUE.plusSeconds(999), DUE.minusSeconds(1), SlaState.MET),
                argumentSet("CASE-E95h closed exactly at due", DUE.plusSeconds(999), DUE, SlaState.MET),
                argumentSet("CASE-E95i closed after due", DUE.plusSeconds(999), DUE.plusSeconds(1), SlaState.MISSED));
    }

    @ParameterizedTest
    @MethodSource("states")
    void slaStateIsComputed(Instant now, Instant closedAt, SlaState expected) {
        assertThat(SlaRules.state(now, START, DUE, closedAt)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "CASE-R80 policy {0}, default {1} -> {2} h")
    @CsvSource(nullValues = "none", textBlock = """
            48,   72,  48
            none, 240, 240
            """)
    void countryPolicyOverridesTheTypeDefault(Integer policy, int typeDefault, int expected) {
        assertThat(SlaRules.slaHours(Optional.ofNullable(policy), typeDefault)).isEqualTo(expected);
    }
}
