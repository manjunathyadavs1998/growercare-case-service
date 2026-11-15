package com.growercare.casesvc.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.testsupport.OpenApiContract;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** SPEC-PLAT-01 R1/R3, examples PLAT-E21/E22 for case-service. */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class CaseOpenApiContractIT extends CaseApiTestBase {

    JsonNode api() throws Exception {
        return OpenApiContract.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    static Stream<Arguments> operations() {
        return Stream.of(
                argumentSet("PLAT-E21a POST /cases", "/cases", "post"),
                argumentSet("PLAT-E21b GET /cases", "/cases", "get"),
                argumentSet("PLAT-E21c GET /cases/{caseId}", "/cases/{caseId}", "get"),
                argumentSet("PLAT-E21d GET /cases/by-no/{caseNo}", "/cases/by-no/{caseNo}", "get"),
                argumentSet("PLAT-E21e POST transitions", "/cases/{caseId}/transitions", "post"),
                argumentSet("PLAT-E21f PUT assignee", "/cases/{caseId}/assignee", "put"),
                argumentSet("PLAT-E21g GET comments", "/cases/{caseId}/comments", "get"),
                argumentSet("PLAT-E21h POST comments", "/cases/{caseId}/comments", "post"),
                argumentSet("PLAT-E21i PUT share", "/cases/{caseId}/shares/{partnerOrgId}", "put"),
                argumentSet("PLAT-E21j DELETE share", "/cases/{caseId}/shares/{partnerOrgId}", "delete"),
                argumentSet("PLAT-E21k GET activity", "/cases/{caseId}/activity", "get"));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void documentedOperation(String path, String method) throws Exception {
        assertThat(OpenApiContract.hasOperation(api(), path, method)).as("%s %s documented", method, path).isTrue();
    }

    @Test
    void platE22BearerSchemeAndContractExport() throws Exception {
        var api = api();
        assertThat(api.path("components").path("securitySchemes").path("bearer").path("scheme").asString()).isEqualTo("bearer");
        assertThat(api.path("security").toString()).contains("bearer");
        assertThat(OpenApiContract.export(api, "case-service")).exists();
    }
}
