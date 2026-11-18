package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.NO_CASE_READ_TEST;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-10 R220–R222 (CASE-E220…E222): case types and their statuses for the UI. */
class CaseTypesIT extends CaseApiTestBase {

    static Stream<Arguments> readers() {
        return Stream.of(argumentSet("CASE-E220 grower.in1", GROWER_IN1), argumentSet("CASE-E220 care.in", CARE_IN));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void activeTypesWithTheirStatuses(GcPrincipal actor) throws Exception {
        as(actor, get("/case-types")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")))
                .andExpect(jsonPath("$[*].caseType", hasItem("PRODUCT_COMPLAINT")))
                .andExpect(jsonPath("$[?(@.caseType == 'PRODUCT_COMPLAINT')].statuses[0].status", contains("SUBMITTED")))
                .andExpect(jsonPath("$[?(@.caseType == 'PRODUCT_COMPLAINT')].statuses[0].initial", contains(true)))
                .andExpect(jsonPath("$[?(@.caseType == 'PRODUCT_COMPLAINT')].statuses[?(@.status == 'CLOSED')].closed", contains(true)))
                .andExpect(jsonPath("$[?(@.caseType == 'PRODUCT_COMPLAINT')].defaultSlaHours").isNotEmpty());
    }

    @Test
    void CASE_E221_inactiveTypesAreNotListed() throws Exception {
        owner.sql("UPDATE ref.case_type SET active = false WHERE case_type_code = 'ADVERSE_INCIDENT'").update();
        try {
            as(CARE_IN, get("/case-types")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].caseType", not(hasItem("ADVERSE_INCIDENT"))));
        } finally {
            owner.sql("UPDATE ref.case_type SET active = true WHERE case_type_code = 'ADVERSE_INCIDENT'").update();
        }
    }

    @Test
    void CASE_E222_withoutCaseReadIs403() throws Exception {
        as(NO_CASE_READ_TEST, get("/case-types")).andExpect(status().isForbidden());
    }
}
