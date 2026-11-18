package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-CASE-07 R210–R214 (CASE-E210…E214): the territory overview, computed with the case list's scope (DSH-2). */
class CaseOverviewIT extends CaseApiTestBase {

    String overview(String query) throws Exception {
        return as(TM_IN, get("/cases/overview" + query)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void CASE_E210_theWholeScopeOrOneTerritory() throws Exception {
        createCase(GROWER_IN1, complaint());
        as(TM_IN, get("/cases/overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.countryCodes", Matchers.contains("IN")))
                .andExpect(jsonPath("$.scope.territoryIds", Matchers.containsInAnyOrder("1001", "1002", "1003")))
                .andExpect(jsonPath("$.openByStatus.SUBMITTED", Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.asOf").exists());
        as(TM_IN, get("/cases/overview?territoryId=1002")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.territoryIds", Matchers.contains("1002")))
                .andExpect(jsonPath("$.openByType.PRODUCT_COMPLAINT", Matchers.greaterThanOrEqualTo(1)));
    }

    @ParameterizedTest(name = "CASE-E211 territory {0}")
    @ValueSource(strings = {"1005", "999999"})
    void aTerritoryOutsideTheDashboardScopeIsNotFound(String territoryId) throws Exception {
        problem(as(TM_IN, get("/cases/overview").param("territoryId", territoryId)), 404, "NOT_FOUND");
    }

    static Stream<Arguments> withoutDashboard() {
        return Stream.of(
                argumentSet("CASE-E212 care.in", CARE_IN),
                argumentSet("CASE-E212 agro.in", AGRO_IN),
                argumentSet("CASE-E212 grower.in1", GROWER_IN1),
                argumentSet("CASE-E212 retailer.in", RETAILER_IN));
    }

    @ParameterizedTest
    @MethodSource("withoutDashboard")
    void noDashboardPermissionIs403(GcPrincipal actor) throws Exception {
        as(actor, get("/cases/overview")).andExpect(status().isForbidden());
    }

    /** All open cases the caller's list shows (pages of 100). */
    List<Map<String, Object>> openInList(String query) throws Exception {
        var items = new ArrayList<Map<String, Object>>();
        String cursor = null;
        do {
            var request = get("/cases?limit=100" + query);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            var json = as(TM_IN, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> page = JsonPath.read(json, "$.items");
            page.stream().filter(i -> i.get("closedAt") == null).forEach(items::add);
            cursor = JsonPath.read(json, "$.nextCursor");
        } while (cursor != null);
        return items;
    }

    static Map<String, Integer> countBy(List<Map<String, Object>> items, Function<Map<String, Object>, Object> key) {
        return items.stream().filter(i -> key.apply(i) != null)
                .collect(Collectors.groupingBy(i -> key.apply(i).toString(), TreeMap::new, Collectors.summingInt(i -> 1)));
    }

    static Stream<Arguments> views() {
        return Stream.of(argumentSet("CASE-E213 whole scope", ""), argumentSet("CASE-E213 Pune only", "1002"));
    }

    /** R213: every number equals what the case list shows to the same caller. */
    @ParameterizedTest
    @MethodSource("views")
    void theOverviewMatchesTheList(String territoryId) throws Exception {
        createCase(GROWER_IN1, complaint());
        var narrowed = territoryId.isEmpty() ? "" : "&territoryId=" + territoryId;
        var list = openInList(narrowed);
        var json = overview(territoryId.isEmpty() ? "" : "?territoryId=" + territoryId);

        assertThat(new TreeMap<>(JsonPath.<Map<String, Integer>>read(json, "$.openByStatus"))).isEqualTo(countBy(list, i -> i.get("status")));
        assertThat(new TreeMap<>(JsonPath.<Map<String, Integer>>read(json, "$.openByType"))).isEqualTo(countBy(list, i -> i.get("caseType")));
        var sla = countBy(list, i -> i.get("slaState"));
        assertThat(JsonPath.<Integer>read(json, "$.sla.atRisk")).isEqualTo(sla.getOrDefault("AT_RISK", 0));
        assertThat(JsonPath.<Integer>read(json, "$.sla.breached")).isEqualTo(sla.getOrDefault("BREACHED", 0));
        List<Map<String, Object>> workload = JsonPath.read(json, "$.workload");
        var perAssignee = new TreeMap<String, Integer>();
        workload.forEach(w -> perAssignee.merge(w.get("assigneeUserId").toString(), (Integer) w.get("open"), Integer::sum));
        assertThat(perAssignee).isEqualTo(countBy(list, i -> i.get("assigneeUserId")));
        assertThat(JsonPath.<Integer>read(json, "$.unassigned"))
                .isEqualTo((int) list.stream().filter(i -> i.get("assigneeUserId") == null).count());
    }

    /** R214 (tests cache for 0 s; the 30 s window itself is OverviewCacheTest). */
    @Test
    void CASE_E214_aNewCaseIsCounted() throws Exception {
        int before = JsonPath.<Map<String, Integer>>read(overview("?territoryId=1002"), "$.openByStatus").getOrDefault("SUBMITTED", 0);
        createCase(GROWER_IN1, complaint());
        assertThat(JsonPath.<Integer>read(overview("?territoryId=1002"), "$.openByStatus.SUBMITTED")).isEqualTo(before + 1);
    }
}
