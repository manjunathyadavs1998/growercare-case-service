package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-07 R200–R204 (CASE-E200…E204): case search by grower, product and batch, always inside the scope. */
class CaseSearchIT extends CaseApiTestBase {

    /** Every item of every page for {@code query} (pages of 100). */
    List<Map<String, Object>> all(GcPrincipal actor, String query) throws Exception {
        var items = new ArrayList<Map<String, Object>>();
        String cursor = null;
        do {
            var request = get("/cases?" + query + "&limit=100");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            var json = as(actor, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            items.addAll(JsonPath.read(json, "$.items"));
            cursor = JsonPath.read(json, "$.nextCursor");
        } while (cursor != null);
        return items;
    }

    static List<Object> ids(List<Map<String, Object>> items) {
        return items.stream().map(i -> i.get("caseId")).toList();
    }

    @Test
    void CASE_E200_careFindsOneGrowersCases() throws Exception {
        var mine = createCase(GROWER_IN1, complaint());
        var other = createCase(CARE_IN, with(complaint(), "growerId", SeedGrowers.id(SeedGrowers.G2),
                "farmId", SeedGrowers.id(SeedGrowers.FARM_G2)));
        var found = all(CARE_IN, "growerId=" + SeedGrowers.G1);
        assertThat(found).isNotEmpty().allSatisfy(i -> assertThat(i.get("growerId")).isEqualTo(SeedGrowers.id(SeedGrowers.G1)));
        assertThat(ids(found)).contains(mine).doesNotContain(other);
    }

    static Stream<Arguments> outsideScope() {
        return Stream.of(
                argumentSet("CASE-E201 another grower's cases", GROWER_IN2),
                argumentSet("CASE-E201 another country's care agent", CARE_BR));
    }

    @ParameterizedTest
    @MethodSource("outsideScope")
    void aGrowerOutsideTheScopeGivesAnEmptyPage(GcPrincipal actor) throws Exception {
        createCase(GROWER_IN1, complaint());
        as(actor, get("/cases").param("growerId", SeedGrowers.id(SeedGrowers.G1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    static Stream<Arguments> productFilters() {
        return Stream.of(
                argumentSet("CASE-E202 by product", "productId=9001", false),
                argumentSet("CASE-E202 by product and batch", "productId=9001&batchNo=AZX2604-117", true));
    }

    @ParameterizedTest
    @MethodSource("productFilters")
    void casesAboutAProductOrBatch(String query, boolean batchOnly) throws Exception {
        var sameBatch = createCase(GROWER_IN1, complaint());
        var otherBatch = createCase(GROWER_IN1, with(complaint(), "batchNo", null));             // same product, no batch
        var found = all(CARE_IN, query);
        assertThat(found).allSatisfy(i -> assertThat(i.get("productId")).isEqualTo("9001"));
        assertThat(ids(found)).contains(sameBatch);
        if (batchOnly) {
            assertThat(found).allSatisfy(i -> assertThat(i.get("batchNo")).isEqualTo("AZX2604-117"));
            assertThat(ids(found)).doesNotContain(otherBatch);
        } else {
            assertThat(ids(found)).contains(otherBatch);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            CASE-E203 grower id not an id   | growerId=abc                       | growerId
            CASE-E203 product id not an id  | productId=abc                      | productId
            CASE-E203 malformed batch       | productId=9001&batchNo=x           | batchNo
            CASE-E203 batch without product | batchNo=AZX2604-117                | batchNo
            """)
    void malformedFiltersAre400(String name, String query, String field) throws Exception {
        problem(as(CARE_IN, get("/cases?" + query)), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @Test
    void CASE_E204_theCursorKeepsItsFilters() throws Exception {
        var older = createCase(GROWER_IN1, complaint());
        var newer = createCase(GROWER_IN1, complaint());
        var query = "growerId=" + SeedGrowers.G1 + "&status=SUBMITTED";
        var first = as(CARE_IN, get("/cases?" + query + "&limit=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseId").value(newer)).andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        as(CARE_IN, get("/cases?" + query + "&limit=1").param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseId").value(older));
        problem(as(CARE_IN, get("/cases?growerId=" + SeedGrowers.G1 + "&status=TRIAGED&limit=1").param("cursor", cursor)),
                400, "VALIDATION_FAILED").andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }
}
