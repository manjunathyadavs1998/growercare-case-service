package com.growercare.casesvc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.domain.RoutingRules.Rule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-04 R88/R89 on the seeded rule set (examples CASE-E84…E88) and edge cases. */
class RoutingRulesTest {

    /** Same as the seed in db/case/common/V4 (India). */
    static final List<Rule> INDIA = List.of(
            new Rule(10, "ADVERSE_INCIDENT", null, null, "IN-STEWARDSHIP"),
            new Rule(20, "PRODUCT_COMPLAINT", "SEEDS", null, "IN-SEEDS-QUALITY"),
            new Rule(30, null, null, 1001L, "IN-MH-FIELD"),
            new Rule(40, "PRODUCT_COMPLAINT", null, null, "IN-CP-COMPLAINTS"),
            new Rule(999, null, null, null, "IN-CARE"));

    static final Set<Long> PUNE = Set.of(1002L, 1001L);       // Pune and its ancestor Maharashtra
    static final Set<Long> NASHIK = Set.of(1003L, 1001L);
    static final Set<Long> KARNATAKA_RURAL = Set.of(1005L, 1004L);

    static Stream<Arguments> routes() {
        return Stream.of(
                argumentSet("CASE-E84 adverse incident beats territory", "ADVERSE_INCIDENT", "CP", PUNE, "IN-STEWARDSHIP"),
                argumentSet("CASE-E85 seeds complaint beats territory", "PRODUCT_COMPLAINT", "SEEDS", NASHIK, "IN-SEEDS-QUALITY"),
                argumentSet("CASE-E86 territory ancestor match beats type rule", "PRODUCT_COMPLAINT", "CP", PUNE, "IN-MH-FIELD"),
                argumentSet("CASE-E87 type rule outside Maharashtra", "PRODUCT_COMPLAINT", "CP", KARNATAKA_RURAL, "IN-CP-COMPLAINTS"),
                argumentSet("CASE-E88 fallback", "AGRONOMY_ADVICE", "CP", KARNATAKA_RURAL, "IN-CARE"),
                argumentSet("CASE-R88 territory rule applies to its own territory too", "AGRONOMY_ADVICE", "BIO", Set.of(1001L), "IN-MH-FIELD"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void lowestPriorityMatchingRuleWins(String caseType, String bu, Set<Long> territoryAndAncestors, String expectedQueue) {
        assertThat(RoutingRules.route(INDIA, caseType, bu, territoryAndAncestors)).contains(expectedQueue);
    }

    @Test
    void caseR89EveryActiveCountryNeedsAFallbackRule() {
        var withoutFallback = List.of(new Rule(10, "ADVERSE_INCIDENT", null, null, "BR-STEWARDSHIP"));
        assertThatCode(() -> RoutingRules.requireFallback(Map.of("IN", INDIA), Set.of("IN"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> RoutingRules.requireFallback(Map.of("IN", INDIA, "BR", withoutFallback), Set.of("IN", "BR")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BR");
        assertThatThrownBy(() -> RoutingRules.requireFallback(Map.of("IN", INDIA), Set.of("IN", "BR")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BR");
    }
}
