package com.growercare.casesvc.adapters;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER_IN;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-11 R230–R232 (CASE-E230…E232): active shares in the case view, as each caller may see them. */
class ShareStateIT extends CaseApiTestBase {

    String sharedWithBoth() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        as(GROWER_IN1, put("/cases/{id}/shares/501", caseId)).andExpect(status().isOk());
        as(GROWER_IN1, put("/cases/{id}/shares/502", caseId)).andExpect(status().isOk());
        return caseId;
    }

    static Stream<Arguments> readers() {
        return Stream.of(
                argumentSet("CASE-E230 the grower sees every share", GROWER_IN1, new String[] {"501", "502"}),
                argumentSet("CASE-E230 staff see every share", CARE_IN, new String[] {"501", "502"}),
                argumentSet("CASE-E231 retailer.in sees only its own org", RETAILER_IN, new String[] {"501"}),
                argumentSet("CASE-E231 retailer2.in sees only its own org", RETAILER2_IN, new String[] {"502"}));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void sharesAsTheCallerMaySeeThem(GcPrincipal actor, String[] expected) throws Exception {
        var caseId = sharedWithBoth();
        getCase(actor, caseId).andExpect(status().isOk()).andExpect(jsonPath("$.sharedWithPartnerOrgIds", contains((Object[]) expected)));
    }

    @Test
    void CASE_E232_theListFollowsAnUnshareAtOnce() throws Exception {
        var caseId = sharedWithBoth();
        as(GROWER_IN1, delete("/cases/{id}/shares/502", caseId)).andExpect(status().isNoContent());
        getCase(GROWER_IN1, caseId).andExpect(jsonPath("$.sharedWithPartnerOrgIds", contains("501")));
    }

    @Test
    void CASE_E230_noSharesIsAnEmptyListAndListsCarryNone() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        getCase(GROWER_IN1, caseId).andExpect(jsonPath("$.sharedWithPartnerOrgIds.length()").value(0));
        as(GROWER_IN1, get("/cases?limit=1")).andExpect(jsonPath("$.items[0].sharedWithPartnerOrgIds").doesNotExist());
    }
}
