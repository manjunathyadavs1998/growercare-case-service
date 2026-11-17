package com.growercare.casesvc.adapters;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;

/** SPEC-CASE-09 R190–R193 (CASE-E190…E194): the case view tells the caller what they may do next. */
class AllowedTransitionsIT extends CaseApiTestBase {

    /** A case of any type for grower G1 (Pune, CP), raised by the grower. */
    static Map<String, Object> caseOf(String type) {
        var body = new LinkedHashMap<String, Object>();
        body.put("growerId", SeedGrowers.id(SeedGrowers.G1));
        body.put("caseType", type);
        body.put("title", "Allowed transitions " + type);
        body.put("description", "SPEC-CASE-09");
        body.put("buCode", "CP");
        return body;
    }

    List<String> allowed(GcPrincipal actor, String caseId) throws Exception {
        var json = getCase(actor, caseId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.allowedTransitions[*].toStatus");
    }

    @Test
    void CASE_E190_E191_staffSeeTheirEdgesAndWhetherTheyMayAssign() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        getCase(CARE_IN, caseId).andExpect(jsonPath("$.allowedTransitions[*].toStatus", hasItem("TRIAGED")))
                .andExpect(jsonPath("$.canAssign").value(true));
        getCase(TM_IN, caseId).andExpect(jsonPath("$.canAssign").value(true));
    }

    @Test
    void CASE_E192_aGrowerHasNoTransitions() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        getCase(GROWER_IN1, caseId).andExpect(jsonPath("$.allowedTransitions.length()").value(0))
                .andExpect(jsonPath("$.canAssign").value(false));
    }

    @Test
    void CASE_E193_anEdgeNeedingAPermissionTheCallerLacksIsNotOffered() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        forceStatus(caseId, "ROOT_CAUSE_DETERMINED", false);
        getCase(CARE_IN, caseId)
                .andExpect(jsonPath("$.allowedTransitions[*].toStatus", not(hasItem("COMPENSATION_PROPOSED"))))
                .andExpect(jsonPath("$.allowedTransitions[*].toStatus", hasItem("NO_COMPENSATION")));
    }

    @Test
    void CASE_E190_listsCarryNoActions() throws Exception {
        createCase(GROWER_IN1, complaint());
        as(CARE_IN, get("/cases?limit=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].allowedTransitions").doesNotExist());
    }

    static Stream<Arguments> matrix() {
        var rows = new java.util.ArrayList<Arguments>();
        for (var actor : List.of(CARE_IN, TM_IN)) {
            for (var type : List.of("AGRONOMY_ADVICE", "PRODUCT_COMPLAINT", "SEED_PERFORMANCE_CLAIM", "ADVERSE_INCIDENT")) {
                var who = actor == CARE_IN ? "care.in" : "tm.in";
                rows.add(argumentSet("CASE-E194 " + who + " × " + type, actor, type));
            }
        }
        return rows.stream();
    }

    /** R191: listed ⇔ accepted. Each edge is tried on its own fresh case, so the rows and edges never interfere. */
    @ParameterizedTest
    @MethodSource("matrix")
    void listedExactlyWhenTheTransitionIsAccepted(GcPrincipal actor, String type) throws Exception {
        var probe = createCase(GROWER_IN1, caseOf(type));
        var initial = JsonPath.<String>read(getCase(actor, probe).andReturn().getResponse().getContentAsString(), "$.status");
        var listed = allowed(actor, probe);
        var edges = owner.sql("SELECT to_status FROM ref.case_transition WHERE case_type_code = ? AND from_status = ? ORDER BY to_status")
                .params(type, initial).query(String.class).list();
        assertThat(edges).as("the type has edges out of its initial status").isNotEmpty();
        for (var to : edges) {
            var caseId = createCase(GROWER_IN1, caseOf(type));
            int result = as(actor, post("/cases/{id}/transitions", caseId).header("If-Match", "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"" + to + "\",\"note\":\"E194\"}"))
                    .andReturn().getResponse().getStatus();
            if (listed.contains(to)) {
                assertThat(result).as("%s → %s is listed, so it is accepted", initial, to).isEqualTo(200);
            } else {
                assertThat(result).as("%s → %s is not listed, so it is refused", initial, to).isIn(403, 422);
            }
        }
    }
}
