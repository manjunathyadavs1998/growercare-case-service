package com.growercare.casesvc.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SPEC-CASE-04 R88/R89: queue routing. A rule matches when each of its non-null columns equals the case's value;
 * a territory rule also matches cases in descendant territories (callers pass the case territory + ancestors).
 * The matching rule with the lowest priority number wins.
 */
public final class RoutingRules {

    private RoutingRules() { }

    /** One row of {@code ref.routing_rule}; null columns are wildcards. */
    public record Rule(int priority, String caseType, String buCode, Long territoryId, String queueCode) {
        public Rule {
            Objects.requireNonNull(queueCode, "queueCode");
        }

        boolean isFallback() {
            return caseType == null && buCode == null && territoryId == null;
        }

        boolean matches(String type, String bu, Set<Long> territoryAndAncestors) {
            return (caseType == null || caseType.equals(type))
                    && (buCode == null || buCode.equals(bu))
                    && (territoryId == null || territoryAndAncestors.contains(territoryId));
        }
    }

    public static Optional<String> route(Collection<Rule> countryRules, String caseType, String buCode, Set<Long> territoryAndAncestors) {
        return countryRules.stream()
                .filter(rule -> rule.matches(caseType, buCode, territoryAndAncestors))
                .min(Comparator.comparingInt(Rule::priority))
                .map(Rule::queueCode);
    }

    /** R89: every active country must have a catch-all rule, so routing can never fail at runtime. */
    public static void requireFallback(Map<String, List<Rule>> rulesByCountry, Set<String> activeCountries) {
        var missing = activeCountries.stream()
                .filter(country -> rulesByCountry.getOrDefault(country, List.of()).stream().noneMatch(Rule::isFallback))
                .sorted()
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No fallback routing rule for active countries: " + missing);
        }
    }
}
