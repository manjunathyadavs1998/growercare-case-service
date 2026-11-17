package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.application.port.GrowerDirectory.GrowerReplica;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.casesvc.support.DevSeedPrincipals;
import com.growercare.casesvc.support.SeedGrowers;
import com.growercare.testsupport.TestJwts;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-06 R140–R145 (CASE-E140…E145): creating cases with the grower replica and the read-through. */
class CaseCreationReplicaIT extends CaseApiTestBase {

    /** A grower known only to the stubbed grower-service (not in the replica yet), ACTIVE in Nashik. */
    private GrowerReplica newGrower(Long userId, String status) {
        var grower = new GrowerReplica(CaseTestDatabase.nextGrowerIdInTestShards("IN"), "IN", 1003L, userId, "Nikhil Jadhav",
                new byte[32], "1234", 502L, status, 0);
        growerDirectory.put(grower);
        return grower;
    }

    private int replicaRows(long growerId) {
        return owner.sql("SELECT count(*) FROM ref.grower WHERE grower_id = ?").param(growerId).query(Integer.class).single();
    }

    private static java.util.Map<String, Object> caseFor(long growerId) {
        return with(complaint(), "growerId", SeedGrowers.id(growerId), "farmId", null);
    }

    @Test
    void CASE_E140_anUnknownGrowerIsReadThroughAndTheCaseIsCreated() throws Exception {
        var grower = newGrower(null, "ACTIVE");
        assertThat(replicaRows(grower.growerId())).isZero();
        createCase(CARE_IN, caseFor(grower.growerId()));
        assertThat(replicaRows(grower.growerId())).isEqualTo(1);
        assertThat(growerDirectory.calls(grower.growerId())).isEqualTo(1);
        var name = owner.sql("SELECT display_name FROM ref.grower WHERE grower_id = ?").param(grower.growerId()).query(String.class).single();
        assertThat(name).isEqualTo("Nikhil Jadhav");
    }

    @Test
    void CASE_E141_aGrowerGrowerServiceDoesNotKnowIs404() throws Exception {
        problem(create(CARE_IN, caseFor(CaseTestDatabase.nextGrowerIdInTestShards("IN")), UUID.randomUUID()), 404, "NOT_FOUND");
    }

    static Stream<Arguments> unavailable() {
        return Stream.of(
                argumentSet("CASE-E142 grower-service down", "down"),
                argumentSet("CASE-E142 territory unknown to case-service (reference-data gap)", "territory"),
                argumentSet("CASE-E142 partner unknown to case-service (reference-data gap)", "partner"));
    }

    @ParameterizedTest
    @MethodSource("unavailable")
    void readThroughFailuresFailClosed(String failure) throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        switch (failure) {
            case "down" -> growerDirectory.failFor(id);
            case "territory" -> growerDirectory.put(new GrowerReplica(id, "IN", 9999L, null, "Gap", new byte[32], "0000", null, "ACTIVE", 0));
            default -> growerDirectory.put(new GrowerReplica(id, "IN", 1003L, null, "Gap", new byte[32], "0000", 999L, "ACTIVE", 0));
        }
        problem(create(CARE_IN, caseFor(id), UUID.randomUUID()), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(replicaRows(id)).isZero();
    }

    static Stream<Arguments> notActive() {
        return Stream.of(
                argumentSet("CASE-E143 G3 is PENDING_CONSENT (assisted, no OTP yet)", SeedGrowers.G3),
                argumentSet("CASE-E143 70001 is INACTIVE after the seed moved (D6)", 70001L));
    }

    @ParameterizedTest
    @MethodSource("notActive")
    void growersWhoAreNotActiveGetNoCases(long growerId) throws Exception {
        problem(create(CARE_IN, caseFor(growerId), UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("case.grower-not-active"));
    }

    @Test
    void CASE_E144_aReplicatedGrowerNeedsNoCallToGrowerService() throws Exception {
        int before = growerDirectory.calls(SeedGrowers.G1);
        createCase(CARE_IN, complaint());
        assertThat(growerDirectory.calls(SeedGrowers.G1)).isEqualTo(before);
    }

    @Test
    void CASE_E145_aSelfRegisteredGrowerRaisesACaseAndSeesItInTheirList() throws Exception {
        long userId = 961;
        var grower = newGrower(userId, "ACTIVE");
        var principal = DevSeedPrincipals.testGrower(userId);
        var caseId = createCase(principal, caseFor(grower.growerId()));
        mvc.perform(get("/cases").header("Authorization", TestJwts.user(principal.subject()).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseId").value(caseId));
    }
}
