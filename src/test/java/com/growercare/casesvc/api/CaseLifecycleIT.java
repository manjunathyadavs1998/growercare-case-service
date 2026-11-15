package com.growercare.casesvc.api;

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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import com.growercare.shard.CaseNumber;
import com.growercare.shard.IdCodec;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** SPEC-CASE-02 ({@code docs/specs/case/02-case-lifecycle.md}); row names are the spec example IDs. */
class CaseLifecycleIT extends CaseApiTestBase {

    // ============================================================================================ create

    @Nested
    class Create {

        /** actor, body, expected fields (json path → value). */
        static Stream<Arguments> accepted() {
            return Stream.of(
                    argumentSet("CASE-E20 grower complaint with product, batch, farm", GROWER_IN1, complaint(),
                            Map.of("status", "SUBMITTED", "countryCode", "IN", "territoryId", "1002", "buCode", "CP", "raisedChannel", "APP")),
                    argumentSet("CASE-E21 advice without product uses buCode", GROWER_IN1,
                            with(complaint(), "caseType", "AGRONOMY_ADVICE", "productId", null, "batchNo", null, "buCode", "SEEDS"),
                            Map.of("status", "SUBMITTED", "buCode", "SEEDS", "raisedChannel", "APP")),
                    argumentSet("CASE-E22 care agent for an assisted grower", CARE_IN,
                            with(complaint(), "growerId", "70004", "farmId", "80004"),
                            Map.of("territoryId", "1005", "raisedChannel", "CARE_CALL", "raisedByUserId", "101")),
                    argumentSet("CASE-E23 territory manager in the field", TM_IN,
                            with(complaint(), "growerId", "70002", "farmId", "80002"),
                            Map.of("territoryId", "1003", "raisedChannel", "FIELD")),
                    argumentSet("CASE-E24 retailer at the shop", RETAILER_IN, complaint(),
                            Map.of("raisedChannel", "RETAILER", "raisedViaPartnerOrgId", "501")));
        }

        @ParameterizedTest
        @MethodSource("accepted")
        void accepted(GcPrincipal actor, Map<String, Object> body, Map<String, String> expected) throws Exception {
            var result = create(actor, body, UUID.randomUUID())
                    .andExpect(status().isCreated())
                    .andExpect(header().string("ETag", "\"0\""))
                    .andExpect(header().string("Location", matchesPattern("/cases/\\d+")))
                    .andExpect(jsonPath("$.version").value(0));
            for (var e : expected.entrySet()) {
                result.andExpect(jsonPath("$." + e.getKey()).value(e.getValue()));
            }
            var json = result.andReturn().getResponse().getContentAsString();
            String caseId = JsonPath.read(json, "$.caseId");
            String caseNo = JsonPath.read(json, "$.caseNo");

            var number = CaseNumber.parse(caseNo).orElseThrow();                       // Damm-valid 15 digits
            assertThat(number.caseNoCc()).isEqualTo(71);
            assertThat(number.shardNo()).isEqualTo(IdCodec.decode(Long.parseLong(caseId)).shardNo());
            Instant created = Instant.parse(JsonPath.read(json, "$.createdAt"));
            Instant slaDue = Instant.parse(JsonPath.read(json, "$.slaDueAt"));
            long slaHours = "AGRONOMY_ADVICE".equals(body.get("caseType")) ? 48 : 240;
            assertThat(Duration.between(created, slaDue).minusHours(slaHours).abs()).isLessThan(Duration.ofMinutes(1));

            assertThat(count("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'CREATED'", caseId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.created'", caseId)).isEqualTo(1);
            getCase(actor, caseId).andExpect(status().isOk());                           // the creator can read it (E24)
        }

        /** actor, body, send Idempotency-Key, status, code, rule (422) or field (400) or null. */
        static Stream<Arguments> rejected() {
            return Stream.of(
                    argumentSet("CASE-E25 other grower's record", GROWER_IN1, with(complaint(), "growerId", "70002", "farmId", null), true, 404, "NOT_FOUND", null),
                    argumentSet("CASE-E26 grower of another country", CARE_IN, with(complaint(), "growerId", "70003", "farmId", null), true, 404, "NOT_FOUND", null),
                    argumentSet("CASE-E27 grower outside territory", TM_IN, with(complaint(), "growerId", "70004", "farmId", null), true, 404, "NOT_FOUND", null),
                    argumentSet("CASE-E28 unknown grower", GROWER_IN1, with(complaint(), "growerId", "99999", "farmId", null), true, 404, "NOT_FOUND", null),
                    argumentSet("CASE-E29 missing Idempotency-Key", GROWER_IN1, complaint(), false, 400, "VALIDATION_FAILED", null),
                    argumentSet("CASE-E30a unknown case type", GROWER_IN1, with(complaint(), "caseType", "NOT_A_TYPE"), true, 400, "VALIDATION_FAILED", "caseType"),
                    argumentSet("CASE-E30b blank title", GROWER_IN1, with(complaint(), "title", " "), true, 400, "VALIDATION_FAILED", "title"),
                    argumentSet("CASE-E30c title too long", GROWER_IN1, with(complaint(), "title", "x".repeat(201)), true, 400, "VALIDATION_FAILED", "title"),
                    argumentSet("CASE-E30d description too long", GROWER_IN1, with(complaint(), "description", "x".repeat(4001)), true, 400, "VALIDATION_FAILED", "description"),
                    argumentSet("CASE-E30e no product and no buCode", GROWER_IN1, with(complaint(), "productId", null, "batchNo", null), true, 400, "VALIDATION_FAILED", "buCode"),
                    argumentSet("CASE-E31 product not registered in BR", GROWER_BR1,
                            with(complaint(), "growerId", "70003", "farmId", "80003", "productId", "9003", "batchNo", null), true, 422, "BUSINESS_RULE_VIOLATION", "case.product-not-registered"),
                    argumentSet("CASE-E32 batch of another product", GROWER_IN1, with(complaint(), "batchNo", "HYC2605-009"), true, 422, "BUSINESS_RULE_VIOLATION", "case.batch-unknown"),
                    argumentSet("CASE-E33 farm of another grower", GROWER_IN1, with(complaint(), "farmId", "80002"), true, 422, "BUSINESS_RULE_VIOLATION", "case.farm-not-of-grower"));
        }

        @ParameterizedTest
        @MethodSource("rejected")
        void rejected(GcPrincipal actor, Map<String, Object> body, boolean withKey, int status, String code, String detail) throws Exception {
            var result = problem(create(actor, body, withKey ? UUID.randomUUID() : null), status, code);
            if (detail != null && status == 422) {
                result.andExpect(jsonPath("$.rule").value(detail));
            } else if (detail != null) {
                result.andExpect(jsonPath("$.errors[0].field").value(detail));
            }
        }

        @Test
        void caseE34RetryWithSameKeyAndBodyReplaysTheOriginal() throws Exception {
            var key = UUID.randomUUID();
            var body = with(complaint(), "title", "Replay check " + key);
            String first = JsonPath.read(create(GROWER_IN1, body, key).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.caseId");
            create(GROWER_IN1, body, key)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.caseId").value(first));
            assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.created'", first)).isEqualTo(1);
            assertThat(owner.sql("SELECT count(*) FROM %s.cases WHERE title = ?".formatted(shardOf(first).schemaName()))
                    .param(body.get("title")).query(Integer.class).single()).isEqualTo(1);
        }

        @Test
        void caseE35SameKeyDifferentBodyIsRejected() throws Exception {
            var key = UUID.randomUUID();
            create(GROWER_IN1, complaint(), key).andExpect(status().isCreated());
            problem(create(GROWER_IN1, with(complaint(), "title", "Something else"), key), 409, "IDEMPOTENCY_KEY_REUSED");
        }
    }

    // ============================================================================================ read

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Read {

        String caseId;
        String caseNo;

        @BeforeAll
        void fixture() throws Exception {
            var json = create(RETAILER_IN, complaint(), UUID.randomUUID()).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            caseId = JsonPath.read(json, "$.caseId");
            caseNo = JsonPath.read(json, "$.caseNo");
        }

        /** actor, expected status for GET by id and by number. */
        static Stream<Arguments> access() {
            return Stream.of(
                    argumentSet("CASE-E36a owner grower", GROWER_IN1, 200),
                    argumentSet("CASE-E36b care agent (country)", CARE_IN, 200),
                    argumentSet("CASE-E36c territory manager", TM_IN, 200),
                    argumentSet("CASE-E36d raising retailer", RETAILER_IN, 200),
                    argumentSet("CASE-E37a other grower", GROWER_IN2, 404),
                    argumentSet("CASE-E37b other country", CARE_BR, 404),
                    argumentSet("CASE-E37c agronomist not assigned", AGRO_IN, 404),
                    argumentSet("CASE-E37d other retailer", RETAILER2_IN, 404));
        }

        @ParameterizedTest
        @MethodSource("access")
        void byIdAndByNumber(GcPrincipal actor, int expected) throws Exception {
            for (var path : List.of("/cases/" + caseId, "/cases/by-no/" + caseNo)) {
                var result = as(actor, get(path)).andExpect(status().is(expected));
                if (expected == 200) {
                    result.andExpect(header().string("ETag", "\"0\"")).andExpect(jsonPath("$.caseId").value(caseId));
                } else {
                    result.andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.detail").value("Case not found"));
                }
            }
        }

        @ParameterizedTest(name = "CASE-E38 malformed or foreign reference {0}")
        @ValueSource(strings = {"/cases/abc", "/cases/0", "/cases/-5", "/cases/by-no/71-0318-27-482139-5",
                "/cases/by-no/123", "/cases/4523009" /* country index 5: not active */})
        void malformedReferencesAreNotFound(String path) throws Exception {
            problem(as(CARE_IN, get(path)), 404, "NOT_FOUND");
        }

        /** actor, expected grower name (CASE-R30). */
        static Stream<Arguments> masking() {
            return Stream.of(
                    argumentSet("CASE-E39a owner sees own name", GROWER_IN1, "Ramesh Patil"),
                    argumentSet("CASE-E39b field staff (FULL)", TM_IN, "Ramesh Patil"),
                    argumentSet("CASE-E39c care agent (MASKED)", CARE_IN, "R***** P****"),
                    argumentSet("CASE-E39d retailer (MASKED)", RETAILER_IN, "R***** P****"));
        }

        @ParameterizedTest
        @MethodSource("masking")
        void growerNameIsMaskedByClearance(GcPrincipal actor, String expectedName) throws Exception {
            getCase(actor, caseId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.growerName").value(expectedName))
                    .andExpect(jsonPath("$.growerPhoneLast4").value("3210"))
                    .andExpect(jsonPath("$.growerPhone").doesNotExist());
        }
    }

    // ============================================================================================ list

    @Nested
    class Lists {

        @Test
        void caseE40KeysetPagesHaveNoDuplicatesOrGaps() throws Exception {
            var tag = UUID.randomUUID().toString();
            var created = new ArrayList<String>();
            for (int i = 0; i < 3; i++) {
                created.add(createCase(CARE_IN, with(complaint(), "growerId", "70004", "farmId", "80004", "title", tag + " " + i)));
            }
            var seen = new ArrayList<String>();
            String cursor = null;
            do {
                var request = get("/cases").param("limit", "2").param("territoryId", "1005");
                if (cursor != null) {
                    request.param("cursor", cursor);
                }
                var json = as(CARE_IN, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                List<String> ids = JsonPath.read(json, "$.items[*].caseId");
                assertThat(ids).hasSizeLessThanOrEqualTo(2);
                seen.addAll(ids);
                cursor = JsonPath.read(json, "$.nextCursor");
            } while (cursor != null);
            assertThat(new HashSet<>(seen)).hasSameSizeAs(seen);                                   // no duplicates
            assertThat(seen).containsAll(created);                                                  // no gaps
            assertThat(seen.stream().map(Long::parseLong).toList()).isSortedAccordingTo((a, b) -> Long.compare(b, a)); // newest first
        }

        @Test
        void caseE41GrowerListsOnlyOwnCases() throws Exception {
            createCase(GROWER_IN2, with(complaint(), "growerId", "70002", "farmId", "80002"));
            var json = as(GROWER_IN2, get("/cases").param("limit", "100")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> growers = JsonPath.read(json, "$.items[*].growerId");
            assertThat(growers).isNotEmpty().containsOnly("70002");
        }

        @Test
        void caseE42AssignedToMeReturnsExactlyTheAssignedCase() throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            as(TM_IN, put("/cases/{id}/assignee", caseId).header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeUserId\":\"102\"}")).andExpect(status().isOk());
            var json = as(AGRO_IN, get("/cases").param("assignedToMe", "true").param("limit", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(json, "$.items[*].caseId");
            assertThat(ids).contains(caseId);
            List<String> assignees = JsonPath.read(json, "$.items[*].assigneeUserId");
            assertThat(assignees).containsOnly("102");
        }

        @ParameterizedTest(name = "CASE-E43 invalid query {0}={1}")
        @org.junit.jupiter.params.provider.CsvSource(textBlock = """
                limit,        0
                limit,        101
                limit,        abc
                cursor,       %%%
                status,       submitted
                caseType,     not-a-type
                territoryId,  x
                """)
        void invalidQueryParametersAre400(String name, String value) throws Exception {
            problem(as(CARE_IN, get("/cases").param(name, value)), 400, "VALIDATION_FAILED");
        }

        @Test
        void caseE44OlderThanTheHotWindowNeedsTheArchive() throws Exception {
            problem(as(CARE_IN, get("/cases").param("createdFrom", Instant.now().minus(Duration.ofDays(120)).toString())),
                    501, "ARCHIVE_NOT_ENABLED");
        }
    }

    // ============================================================================================ transitions

    @Nested
    class Transitions {

        ResultActions transition(GcPrincipal actor, String caseId, String ifMatch, String toStatus, String note) throws Exception {
            var request = post("/cases/{id}/transitions", caseId).contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(note == null ? Map.of("toStatus", toStatus) : Map.of("toStatus", toStatus, "note", note)));
            if (ifMatch != null) {
                request.header("If-Match", ifMatch);
            }
            return as(actor, request);
        }

        @Test
        void caseE45TriageBumpsVersionAndRecordsActivityAndEvent() throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            transition(CARE_IN, caseId, "\"0\"", "TRIAGED", "Checking batch")
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"1\""))
                    .andExpect(jsonPath("$.status").value("TRIAGED"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.closedAt").value(nullValue()));
            assertThat(count("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'STATUS_CHANGED' AND note = 'Checking batch'", caseId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.status_changed'", caseId)).isEqualTo(1);
        }

        @Test
        void caseE46ClosingSetsClosedAt() throws Exception {
            var caseId = createCase(GROWER_IN1, with(complaint(), "caseType", "AGRONOMY_ADVICE", "productId", null, "batchNo", null, "buCode", "CP"));
            transition(CARE_IN, caseId, "\"0\"", "IN_PROGRESS", null).andExpect(status().isOk());
            transition(CARE_IN, caseId, "\"1\"", "ADVICE_GIVEN", null).andExpect(status().isOk());
            transition(CARE_IN, caseId, "\"2\"", "CLOSED", "Advice accepted")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closedAt").isNotEmpty());
        }

        /** actor, status to force before the call, toStatus, If-Match, expected status, code, rule. */
        static Stream<Arguments> rejected() {
            return Stream.of(
                    argumentSet("CASE-E47 edge not in the workflow", CARE_IN, null, "CLOSED", "\"0\"", 422, "BUSINESS_RULE_VIOLATION", "case.transition-not-allowed"),
                    argumentSet("CASE-E48 grower lacks case:transition", GROWER_IN1, null, "TRIAGED", "\"0\"", 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E49 edge needs compensation:propose", CARE_IN, "ROOT_CAUSE_DETERMINED", "COMPENSATION_PROPOSED", "\"0\"", 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E52a If-Match missing", CARE_IN, null, "TRIAGED", null, 428, "PRECONDITION_REQUIRED", null),
                    argumentSet("CASE-E52b stale If-Match", CARE_IN, null, "TRIAGED", "\"7\"", 412, "STALE_VERSION", null),
                    argumentSet("CASE-E52c malformed If-Match", CARE_IN, null, "TRIAGED", "seven", 400, "VALIDATION_FAILED", null),
                    argumentSet("CASE-E37 out of scope stays 404", CARE_BR, null, "TRIAGED", "\"0\"", 404, "NOT_FOUND", null));
        }

        @ParameterizedTest
        @MethodSource("rejected")
        void rejected(GcPrincipal actor, String forceStatus, String toStatus, String ifMatch, int status, String code, String rule) throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            if (forceStatus != null) {
                forceStatus(caseId, forceStatus, false);
            }
            var result = problem(transition(actor, caseId, ifMatch, toStatus, null), status, code);
            if (rule != null) {
                result.andExpect(jsonPath("$.rule").value(rule));
            }
            getCase(CARE_IN, caseId).andExpect(jsonPath("$.version").value(0));         // nothing changed
        }
    }

    // ============================================================================================ assignment

    @Nested
    class Assignment {

        ResultActions assign(GcPrincipal actor, String caseId, String assignee) throws Exception {
            return as(actor, put("/cases/{id}/assignee", caseId).header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeUserId\":\"" + assignee + "\"}"));
        }

        /** actor, assignee, expected status, code, rule. */
        static Stream<Arguments> assignments() {
            return Stream.of(
                    argumentSet("CASE-E50 territory manager assigns the Pune agronomist", TM_IN, "102", 200, null, null),
                    argumentSet("CASE-E53a care agent assigns", CARE_IN, "102", 200, null, null),
                    argumentSet("CASE-E53b grower lacks case:assign", GROWER_IN1, "102", 403, "PERMISSION_DENIED", null),
                    argumentSet("CASE-E51a staff of another country", TM_IN, "105", 422, "BUSINESS_RULE_VIOLATION", "case.assignee-not-eligible"),
                    argumentSet("CASE-E51b a grower", TM_IN, "1", 422, "BUSINESS_RULE_VIOLATION", "case.assignee-not-eligible"),
                    argumentSet("CASE-E51c unknown user", TM_IN, "999", 422, "BUSINESS_RULE_VIOLATION", "case.assignee-not-eligible"));
        }

        @ParameterizedTest
        @MethodSource("assignments")
        void assign(GcPrincipal actor, String assignee, int status, String code, String rule) throws Exception {
            var caseId = createCase(GROWER_IN1, complaint());
            var result = assign(actor, caseId, assignee);
            if (status == 200) {
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.assigneeUserId").value(assignee))
                        .andExpect(jsonPath("$.version").value(1));
                getCase(AGRO_IN, caseId).andExpect(status().isOk());                    // the assignee can now see it
                assertThat(count("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.assigned'", caseId)).isEqualTo(1);
            } else {
                var problem = problem(result, status, code);
                if (rule != null) {
                    problem.andExpect(jsonPath("$.rule").value(rule));
                }
            }
        }
    }
}
