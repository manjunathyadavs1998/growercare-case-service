package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.TM_IN;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SPEC-CASE-02 R24/R29 (CASE-E30f, CASE-E38): every id input that matches the id pattern but is above
 * {@code Long.MAX_VALUE} is a 400 on the field (body, query) or the uniform 404 (path), never a 500.
 */
class IdOverflowIT extends CaseApiTestBase {

    static final String BIG = "9999999999999999999";

    /** request built for an existing case id; actor; expected status; expected error field (null for 404). */
    static Stream<Arguments> inputs() {
        Function<String, MockHttpServletRequestBuilder> create = caseId -> post("/cases").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(json(with(complaint(), "productId", BIG, "batchNo", null)));
        return Stream.of(
                argumentSet("CASE-E30f productId", create, GROWER_IN1, 400, "productId"),
                argumentSet("CASE-E30f territoryId (query)", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> get("/cases").param("territoryId", BIG), CARE_IN, 400, "territoryId"),
                argumentSet("CASE-E30f assigneeUserId", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> put("/cases/{id}/assignee", caseId).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"assigneeUserId\":\"" + BIG + "\"}"), TM_IN, 400, "assigneeUserId"),
                argumentSet("CASE-E30f replyToCommentId", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> post("/cases/{id}/comments", caseId).contentType(MediaType.APPLICATION_JSON)
                                .content(comment("\"replyToCommentId\":\"" + BIG + "\"")), GROWER_IN1, 400, "replyToCommentId"),
                argumentSet("CASE-E30f attachmentIds", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> post("/cases/{id}/comments", caseId).contentType(MediaType.APPLICATION_JSON)
                                .content(comment("\"attachmentIds\":[\"" + BIG + "\"]")), GROWER_IN1, 400, "attachmentIds"),
                argumentSet("CASE-E30f share partnerOrgId", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> put("/cases/{id}/shares/{org}", caseId, BIG), GROWER_IN1, 400, "partnerOrgId"),
                argumentSet("CASE-E30f unshare partnerOrgId", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> delete("/cases/{id}/shares/{org}", caseId, BIG), GROWER_IN1, 400, "partnerOrgId"),
                argumentSet("CASE-E38 attachment path id (complete)", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> post("/cases/{id}/attachments/{a}/complete", caseId, BIG), GROWER_IN1, 404, null),
                argumentSet("CASE-E38 attachment path id (download)", (Function<String, MockHttpServletRequestBuilder>)
                        caseId -> get("/cases/{id}/attachments/{a}/download", caseId, BIG), GROWER_IN1, 404, null));
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void overflowIsAClientErrorNever500(Function<String, MockHttpServletRequestBuilder> request, GcPrincipal actor,
                                        int status, String field) throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());
        var result = problem(as(actor, request.apply(caseId)), status, status == 404 ? "NOT_FOUND" : "VALIDATION_FAILED");
        if (field != null) {
            result.andExpect(jsonPath("$.errors[*].field", hasItem(field)));
        }
    }

    private static String comment(String extra) {
        return "{\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"visibility\":\"PUBLIC\",\"body\":\"overflow test\"," + extra + "}";
    }

    private static String json(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
