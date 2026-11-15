package com.growercare.casesvc.security;

import static com.growercare.casesvc.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_BR1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.adapters.out.persistence.CaseAccessScope;
import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.ResourceScope;
import com.growercare.security.ScopeFilter;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Isolation matrix (ACC-11, ACC-12, BRD §3) on real data: five cases across countries, territories, BUs, partners
 * and shares; every dev-seed user must see exactly the expected subset, through the SQL {@link ScopeFilter} AND
 * through the in-memory {@link PolicyEnforcer} (the two must never disagree).
 */
class CaseIsolationIT {

    record Fixture(String name, long growerId, Long userIdOfGrower, String country, long territory, String bu,
                   Long viaPartner, Long assignee, Long sharedWith) { }

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("A: Pune CP via retailer 501, assigned agronomist", 70001, 1L, "IN", 1002, "CP", 501L, 102L, null),
            new Fixture("B: Nashik SEEDS", 70002, 2L, "IN", 1003, "SEEDS", null, null, null),
            new Fixture("C: Karnataka CP, assisted grower", 70004, null, "IN", 1005, "CP", null, null, null),
            new Fixture("D: Brazil CP", 70003, 3L, "BR", 2002, "CP", null, null, null),
            new Fixture("E: Pune SEEDS shared with retailer 502", 70001, 1L, "IN", 1002, "SEEDS", null, null, 502L));

    static final Clock CLOCK = Clock.systemUTC();
    static final ScopeFilter FILTER = new ScopeFilter(CLOCK);
    static final PolicyEnforcer POLICY = new PolicyEnforcer(CLOCK);
    static final ShardRouter ROUTER = CaseTestDatabase.ROUTER;

    static JdbcClient app;
    static final Map<Long, String> CASE_NAMES = new HashMap<>();
    static final Map<Long, ShardId> CASE_SHARDS = new HashMap<>();
    static final Map<Long, ResourceScope> CASE_SCOPES = new HashMap<>();

    @BeforeAll
    static void insertCases() {
        app = JdbcClient.create(CaseTestDatabase.migrated().appDataSource());
        for (var f : FIXTURES) {
            var shard = ROUTER.forGrower(f.country(), f.growerId());
            long caseId = app.sql("""
                    INSERT INTO %s.cases (case_type_code, status_code, territory_id, bu_code, grower_id, raised_by_user_id,
                                          raised_channel, raised_via_partner_org_id, assignee_user_id, title, description)
                    VALUES ('PRODUCT_COMPLAINT', 'SUBMITTED', :territory, :bu, :grower, 1, :channel, :partner, :assignee,
                            'Isolation fixture', 'Isolation fixture')
                    RETURNING case_id""".formatted(shard.schemaName()))
                    .param("territory", f.territory()).param("bu", f.bu()).param("grower", f.growerId())
                    .param("channel", f.viaPartner() == null ? "APP" : "RETAILER")
                    .param("partner", f.viaPartner()).param("assignee", f.assignee())
                    .query(Long.class).single();
            if (f.sharedWith() != null) {
                app.sql("INSERT INTO %s.case_share (case_id, partner_org_id, shared_by_user_id, consent_given_at) VALUES (?, ?, 1, now())"
                        .formatted(shard.schemaName())).params(caseId, f.sharedWith()).update();
            }
            var partners = new HashSet<Long>();
            if (f.viaPartner() != null) partners.add(f.viaPartner());
            if (f.sharedWith() != null) partners.add(f.sharedWith());
            CASE_NAMES.put(caseId, f.name().substring(0, 1));
            CASE_SHARDS.put(caseId, shard);
            CASE_SCOPES.put(caseId, new ResourceScope(f.country(), f.territory(), f.bu(), f.userIdOfGrower(), partners, f.assignee()));
        }
    }

    /** SPEC-CASE-01 isolation matrix: principal, expected visible cases. */
    static Stream<Arguments> matrix() {
        return Stream.of(
                argumentSet("CASE-E01 grower.in1 sees own A, E", GROWER_IN1, Set.of("A", "E")),
                argumentSet("CASE-E02 grower.in2 sees own B", GROWER_IN2, Set.of("B")),
                argumentSet("CASE-E03 grower.br1 sees own D", GROWER_BR1, Set.of("D")),
                argumentSet("CASE-E04 care.in sees all of India", CARE_IN, Set.of("A", "B", "C", "E")),
                argumentSet("CASE-E05 care.br sees Brazil only", CARE_BR, Set.of("D")),
                argumentSet("CASE-E06 agro.in sees assigned Pune CP", AGRO_IN, Set.of("A")),
                argumentSet("CASE-E07 tm.in sees Maharashtra incl. districts", TM_IN, Set.of("A", "B", "E")),
                argumentSet("CASE-E08 retailer.in sees cases raised via its shop", RETAILER_IN, Set.of("A")),
                argumentSet("CASE-E09 retailer2.in sees the shared case", RETAILER2_IN, Set.of("E")));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void sqlScopeFilterReturnsExactlyTheExpectedCases(GcPrincipal principal, Set<String> expected) {
        var visible = CASE_SHARDS.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList())))
                .entrySet().stream()
                .flatMap(byShard -> {
                    var predicate = CaseAccessScope.predicate(FILTER, principal, "case:read", byShard.getKey());
                    return app.sql("SELECT c.case_id FROM %s.cases c WHERE c.case_id IN (:ids) AND %s"
                                    .formatted(byShard.getKey().schemaName(), predicate.sql()))
                            .param("ids", byShard.getValue())
                            .params(predicate.params())
                            .query(Long.class).list().stream();
                })
                .map(CASE_NAMES::get)
                .collect(Collectors.toSet());
        assertThat(visible).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void inMemoryPolicyAgreesWithSql(GcPrincipal principal, Set<String> expected) {
        var permitted = CASE_SCOPES.entrySet().stream()
                .filter(e -> POLICY.permits(principal, "case:read", e.getValue()))
                .map(e -> CASE_NAMES.get(e.getKey()))
                .collect(Collectors.toSet());
        assertThat(permitted).isEqualTo(expected);
    }

    /** CASE-E09 (second half): once the share is revoked the partner loses access. */
    @Test
    void caseE09RevokedShareRemovesPartnerAccess() {
        var principal = RETAILER2_IN;
        var shared = CASE_NAMES.entrySet().stream().filter(e -> e.getValue().equals("E")).findFirst().orElseThrow().getKey();
        var shard = CASE_SHARDS.get(shared);
        app.sql("UPDATE %s.case_share SET revoked_at = now(), revoked_by_user_id = 1 WHERE case_id = ?".formatted(shard.schemaName()))
                .param(shared).update();
        try {
            var predicate = CaseAccessScope.predicate(FILTER, principal, "case:read", shard);
            var rows = app.sql("SELECT c.case_id FROM %s.cases c WHERE c.case_id = :id AND %s".formatted(shard.schemaName(), predicate.sql()))
                    .param("id", shared).params(predicate.params()).query(Long.class).list();
            assertThat(rows).isEmpty();
        } finally {
            app.sql("UPDATE %s.case_share SET revoked_at = NULL, revoked_by_user_id = NULL WHERE case_id = ?".formatted(shard.schemaName()))
                    .param(shared).update();
        }
    }
}
