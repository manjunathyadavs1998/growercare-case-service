package com.growercare.casesvc.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SPEC-CASE-04 R80, R81, R86: SLA hours, timer arithmetic and computed SLA state. Pure, unit-tested. */
public final class SlaRules {

    private SlaRules() { }

    /** Timer thresholds as a percentage of the SLA window. */
    public enum TimerKind {
        WARN_50(50), WARN_75(75), WARN_90(90), BREACH(100);

        private final int percent;

        TimerKind(int percent) {
            this.percent = percent;
        }

        public int percent() {
            return percent;
        }
    }

    public record Timer(TimerKind kind, Instant dueAt) { }

    /** Computed, never stored (decision D6). */
    public enum SlaState { ON_TRACK, AT_RISK, BREACHED, MET, MISSED }

    /** Fraction of the window at which a case becomes AT_RISK (same as the WARN_75 threshold). */
    static final int AT_RISK_PERCENT = 75;

    /** R80: the country policy wins over the case type's default. */
    public static int slaHours(Optional<Integer> countryPolicyHours, int caseTypeDefaultHours) {
        return countryPolicyHours.orElse(caseTypeDefaultHours);
    }

    /** R81: one timer per threshold, at that percentage of [start, due]. */
    public static List<Timer> timers(Instant start, Instant due) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(due);
        return Arrays.stream(TimerKind.values())
                .map(kind -> new Timer(kind, fractionOf(start, due, kind.percent())))
                .toList();
    }

    /** R86: state at {@code now}. {@code start} = SLA start (creation or reopen). */
    public static SlaState state(Instant now, Instant start, Instant due, Instant closedAt) {
        if (due == null) {
            return SlaState.ON_TRACK;
        }
        if (closedAt != null) {
            return closedAt.isAfter(due) ? SlaState.MISSED : SlaState.MET;
        }
        if (!now.isBefore(due)) {
            return SlaState.BREACHED;
        }
        return !now.isBefore(fractionOf(start, due, AT_RISK_PERCENT)) ? SlaState.AT_RISK : SlaState.ON_TRACK;
    }

    private static Instant fractionOf(Instant start, Instant due, int percent) {
        long windowMillis = Duration.between(start, due).toMillis();
        return start.plusMillis(windowMillis * percent / 100);
    }
}
