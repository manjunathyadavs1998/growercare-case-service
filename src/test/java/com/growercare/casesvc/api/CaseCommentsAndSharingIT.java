package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** SPEC-CASE-03 ({@code docs/specs/case/03-comments-and-sharing.md}); row names are the spec example IDs. */
class CaseCommentsAndSharingIT extends CaseApiTestBase {

    /** "Case A": raised by retailer.in for grower 70001, so grower.in1, care.in and retailer.in can all read it. */
    String caseA() throws Exception {
        return createCase(RETAILER_IN, complaint());
    }

    ResultActions comment(GcPrincipal actor, String caseId, String visibility, String body, String clientMsgId, String replyTo) throws Exception {
        var json = new LinkedHashMap<String, Object>();
        json.put("clientMsgId", clientMsgId);
        json.put("visibility", visibility);
        json.put("body", body);
        if (replyTo != null) {
            json.put("replyToCommentId", replyTo);
        }
        return as(actor, post("/cases/{id}/comments", caseId).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(json)));
    }

    String commentId(GcPrincipal actor, String caseId, String visibility) throws Exception {
        return JsonPath.read(comment(actor, caseId, visibility, visibility + " note", UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.commentId");
    }

    // ============================================================================================ posting

    @Nested
    class Posting {

        static Stream<Arguments> accepted() {
            return Stream.of(
                    argumentSet("CASE-E60 care agent posts PUBLIC", CARE_IN, "PUBLIC"),
                    argumentSet("CASE-E60b care agent posts INTERNAL", CARE_IN, "INTERNAL"),
                    argumentSet("CASE-E61 grower posts PUBLIC on own case", GROWER_IN1, "PUBLIC"),
                    argumentSet("CASE-E62 raising retailer posts PARTNER", RETAILER_IN, "PARTNER"));
        }

        @ParameterizedTest
        @MethodSource("accepted")
        void accepted(GcPrincipal actor, String visibility) throws Exception {
            var caseId = caseA();
            comment(actor, caseId, visibility, "We are checking the batch.", UUID.randomUUID().toString(), null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.caseId").value(caseId))
                    .andExpect(jsonPath("$.visibility").value(visibility))
                    .andExpect(jsonPath("$.authorUserId").value(Long.toString(actor.userId())));
            getCase(CARE_IN, caseId).andExpect(jsonPath("$.version").value(0));             // D7: case row untouched
            assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE event_type = 'comment.posted' AND payload -> 'data' ->> 'caseId' = ?::text", caseId)).isEqualTo(1);
        }

        /** actor, visibility, body, clientMsgId, expected status, code, field (400) or null. */
        static Stream<Arguments> rejected() {
            return Stream.of(
                    argumentSet("CASE-E63 grower cannot post INTERNAL", GROWER_IN1, "INTERNAL", "hi", UUID.randomUUID().toString(), 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E64 retailer cannot post PUBLIC", RETAILER_IN, "PUBLIC", "hi", UUID.randomUUID().toString(), 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E65 care cannot post PARTNER", CARE_IN, "PARTNER", "hi", UUID.randomUUID().toString(), 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E66 retailer without access", RETAILER2_IN, "PARTNER", "hi", UUID.randomUUID().toString(), 404, "NOT_FOUND", null),
                    argumentSet("CASE-E70a blank body", CARE_IN, "PUBLIC", "   ", UUID.randomUUID().toString(), 400, "VALIDATION_FAILED", "body"),
                    argumentSet("CASE-E70b body too long", CARE_IN, "PUBLIC", "x".repeat(4001), UUID.randomUUID().toString(), 400, "VALIDATION_FAILED", "body"),
                    argumentSet("CASE-E70c clientMsgId not a UUID", CARE_IN, "PUBLIC", "hi", "x", 400, "VALIDATION_FAILED", "clientMsgId"),
                    argumentSet("CASE-E70d unknown visibility", CARE_IN, "SECRET", "hi", UUID.randomUUID().toString(), 400, "VALIDATION_FAILED", null));
        }

        @ParameterizedTest
        @MethodSource("rejected")
        void rejected(GcPrincipal actor, String visibility, String body, String clientMsgId, int status, String code, String field) throws Exception {
            var result = problem(comment(actor, caseA(), visibility, body, clientMsgId, null), status, code);
            if (field != null) {
                result.andExpect(jsonPath("$.errors[0].field").value(field));
            }
        }

        @Test
        void caseE67RetryWithSameClientMsgIdIsIdempotent() throws Exception {
            var caseId = caseA();
            var msgId = UUID.randomUUID().toString();
            String first = JsonPath.read(comment(GROWER_IN1, caseId, "PUBLIC", "Photos attached", msgId, null)
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.commentId");
            comment(GROWER_IN1, caseId, "PUBLIC", "Photos attached", msgId, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.commentId").value(first));
            assertThat(count("SELECT count(*) FROM {shard}.case_comment WHERE case_id = ?", caseId)).isEqualTo(1);
        }

        /** CASE-E68: actor, expected status on a CLOSED case. */
        static Stream<Arguments> closedCase() {
            return Stream.of(
                    argumentSet("CASE-E68a grower cannot post on a closed case", GROWER_IN1, "PUBLIC", 422),
                    argumentSet("CASE-E68b partner cannot post on a closed case", RETAILER_IN, "PARTNER", 422),
                    argumentSet("CASE-E68c staff still can", CARE_IN, "PUBLIC", 201));
        }

        @ParameterizedTest
        @MethodSource("closedCase")
        void closedCases(GcPrincipal actor, String visibility, int expected) throws Exception {
            var caseId = caseA();
            forceStatus(caseId, "CLOSED", true);
            var result = comment(actor, caseId, visibility, "after closure", UUID.randomUUID().toString(), null).andExpect(status().is(expected));
            if (expected == 422) {
                result.andExpect(jsonPath("$.rule").value("comment.case-closed"));
            }
        }

        @Test
        void caseE69ReplyMustTargetACommentOfTheSameCase() throws Exception {
            var otherCaseComment = commentId(CARE_IN, caseA(), "PUBLIC");
            problem(comment(CARE_IN, caseA(), "PUBLIC", "reply", UUID.randomUUID().toString(), otherCaseComment), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("comment.reply-invalid"));
        }
    }

    // ============================================================================================ reading

    @Nested
    class Reading {

        /** CASE-E71: actor, visibilities expected in the feed. */
        static Stream<Arguments> readers() {
            return Stream.of(
                    argumentSet("CASE-E71a grower reads PUBLIC", GROWER_IN1, Set.of("PUBLIC")),
                    argumentSet("CASE-E71b retailer reads PUBLIC + PARTNER", RETAILER_IN, Set.of("PUBLIC", "PARTNER")),
                    argumentSet("CASE-E71c staff read all", CARE_IN, Set.of("PUBLIC", "INTERNAL", "PARTNER")));
        }

        @ParameterizedTest
        @MethodSource("readers")
        void visibilityFilter(GcPrincipal actor, Set<String> expected) throws Exception {
            var caseId = caseA();
            commentId(CARE_IN, caseId, "PUBLIC");
            commentId(CARE_IN, caseId, "INTERNAL");
            commentId(RETAILER_IN, caseId, "PARTNER");
            var json = as(actor, get("/cases/{id}/comments", caseId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> visibilities = JsonPath.read(json, "$.items[*].visibility");
            assertThat(new HashSet<>(visibilities)).isEqualTo(expected);
        }

        @Test
        void caseE72CommentPagesAreNewestFirstWithoutDuplicates() throws Exception {
            var caseId = caseA();
            var posted = List.of(commentId(CARE_IN, caseId, "PUBLIC"), commentId(CARE_IN, caseId, "PUBLIC"), commentId(CARE_IN, caseId, "PUBLIC"));
            var seen = new ArrayList<String>();
            String cursor = null;
            do {
                var request = get("/cases/{id}/comments", caseId).param("limit", "2");
                if (cursor != null) {
                    request.param("cursor", cursor);
                }
                var json = as(CARE_IN, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                seen.addAll(JsonPath.<List<String>>read(json, "$.items[*].commentId"));
                cursor = JsonPath.read(json, "$.nextCursor");
            } while (cursor != null);
            assertThat(seen).containsExactlyElementsOf(posted.reversed());
        }

        @ParameterizedTest(name = "CASE-E72b invalid limit {0}")
        @ValueSource(strings = {"0", "101", "x"})
        void invalidLimit(String limit) throws Exception {
            problem(as(CARE_IN, get("/cases/{id}/comments", caseA()).param("limit", limit)), 400, "VALIDATION_FAILED");
        }
    }

    // ============================================================================================ sharing

    @Nested
    class Sharing {

        ResultActions share(GcPrincipal actor, String caseId, String partnerOrgId) throws Exception {
            return as(actor, put("/cases/{id}/shares/{p}", caseId, partnerOrgId));
        }

        @Test
        void caseE73E74E78ShareIsIdempotentAndRevocable() throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            getCase(RETAILER2_IN, caseId).andExpect(status().isNotFound());
            share(GROWER_IN1, caseId, "502").andExpect(status().isOk()).andExpect(jsonPath("$.revokedAt").doesNotExist());   // E73
            getCase(RETAILER2_IN, caseId).andExpect(status().isOk());
            share(GROWER_IN1, caseId, "502").andExpect(status().isOk());                                                      // E74
            assertThat(count("SELECT count(*) FROM {shard}.case_share WHERE case_id = ? AND revoked_at IS NULL", caseId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'SHARED'", caseId)).isEqualTo(1);

            as(GROWER_IN1, delete("/cases/{id}/shares/{p}", caseId, "502")).andExpect(status().isNoContent());               // E78
            getCase(RETAILER2_IN, caseId).andExpect(status().isNotFound());
            assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.unshared'", caseId)).isEqualTo(1);
        }

        /** actor, partner org, expected status, code, rule. */
        static Stream<Arguments> rejected() {
            return Stream.of(
                    argumentSet("CASE-E75a unknown partner", GROWER_IN1, "999", 422, "BUSINESS_RULE_VIOLATION", "share.partner-invalid"),
                    argumentSet("CASE-E75b partner of another country", GROWER_IN1, "504", 422, "BUSINESS_RULE_VIOLATION", "share.partner-invalid"),
                    argumentSet("CASE-E76a staff cannot consent for the grower", CARE_IN, "502", 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E76b retailer cannot share", RETAILER_IN, "502", 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E77 other grower", GROWER_IN2, "502", 404, "NOT_FOUND", null));
        }

        @ParameterizedTest
        @MethodSource("rejected")
        void rejected(GcPrincipal actor, String partner, int status, String code, String rule) throws Exception {
            var result = problem(share(actor, createCase(GROWER_IN1, complaint()), partner), status, code);
            if (rule != null) {
                result.andExpect(jsonPath("$.rule").value(rule));
            }
        }
    }

    // ============================================================================================ activity

    @Nested
    class Activity {

        /** CASE-E79: actor, whether staff notes are visible. */
        static Stream<Arguments> readers() {
            return Stream.of(
                    argumentSet("CASE-E79a staff see notes", CARE_IN, true),
                    argumentSet("CASE-E79b grower sees the trail without notes", GROWER_IN1, false));
        }

        @ParameterizedTest
        @MethodSource("readers")
        void activityTrail(GcPrincipal actor, boolean notesVisible) throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            as(CARE_IN, post("/cases/{id}/transitions", caseId).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toStatus\":\"TRIAGED\",\"note\":\"Internal: likely misuse\"}")).andExpect(status().isOk());
            var json = as(actor, get("/cases/{id}/activity", caseId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> types = JsonPath.read(json, "$[*].type");
            assertThat(types).containsExactly("CREATED", "STATUS_CHANGED");
            List<Map<String, Object>> rows = JsonPath.read(json, "$[*]");
            var note = rows.get(1).get("note");
            assertThat(note).isEqualTo(notesVisible ? "Internal: likely misuse" : null);
        }
    }
}
