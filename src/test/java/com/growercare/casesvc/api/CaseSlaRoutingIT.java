package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_BR1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.adapters.in.scheduling.ScheduledJobs;
import com.growercare.casesvc.application.MaintenanceJobs;
import com.growercare.casesvc.application.SlaTimerPoller;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.GcPrincipal;
import com.growercare.shard.IdCodec;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** SPEC-CASE-04 ({@code docs/specs/case/04-sla-routing-maintenance.md}); row names are the spec example IDs. */
class CaseSlaRoutingIT extends CaseApiTestBase {

    @Autowired
    SlaTimerPoller poller;
    @Autowired
    MaintenanceJobs maintenance;
    @Autowired
    ScheduledJobs scheduledJobs;
    @Autowired
    MeterRegistry meters;

    static Map<String, Object> advice(String grower, String farm) {
        return with(complaint(), "growerId", grower, "farmId", farm, "caseType", "AGRONOMY_ADVICE",
                "productId", null, "batchNo", null, "buCode", "CP");
    }

    String json(GcPrincipal actor, String caseId) throws Exception {
        return getCase(actor, caseId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    int sqlCount(String sql, String caseId) {
        return count(sql, caseId);
    }

    /** Owner-role shortcut: make timers of a case due now (kind = null for all). */
    void forceTimersDue(String caseId, String kind) {
        owner.sql("UPDATE %s.case_timer SET due_at = now() - interval '1 minute' WHERE case_id = ? AND (?::text IS NULL OR kind = ?::text)"
                        .formatted(shardOf(caseId).schemaName()))
                .param(numericId(caseId)).param(kind).param(kind).update();
    }

    /** API IDs are strings (convention 2); a non-numeric one is a test failure, not an uncaught parse error. */
    static long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new AssertionError("API returned a non-numeric id: " + id, e);
        }
    }

    void transition(GcPrincipal actor, String caseId, int version, String to) throws Exception {
        as(actor, post("/cases/{id}/transitions", caseId).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"" + to + "\"}"))
                .andExpect(status().isOk());
    }

    // ============================================================================================ SLA hours + timers

    @Nested
    class SlaAndTimers {

        /** actor, body, expected SLA hours. */
        static Stream<Arguments> slaHours() {
            return Stream.of(
                    argumentSet("CASE-E80 IN adverse incident uses the country policy (48 h)", GROWER_IN1,
                            with(complaint(), "caseType", "ADVERSE_INCIDENT"), 48),
                    argumentSet("CASE-E81 BR adverse incident uses the country policy (72 h)", GROWER_BR1,
                            with(complaint(), "growerId", "70003", "farmId", "80003", "caseType", "ADVERSE_INCIDENT"), 72),
                    argumentSet("CASE-E82 complaint uses the type default (240 h)", GROWER_IN1, complaint(), 240));
        }

        @ParameterizedTest
        @MethodSource("slaHours")
        void slaHoursPerCountryAndType(GcPrincipal actor, Map<String, Object> body, int hours) throws Exception {
            var json = create(actor, body, UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            Instant created = Instant.parse(JsonPath.read(json, "$.createdAt"));
            Instant due = Instant.parse(JsonPath.read(json, "$.slaDueAt"));
            assertThat(Duration.between(created, due).minusHours(hours).abs()).isLessThan(Duration.ofMinutes(1));
            assertThat((String) JsonPath.read(json, "$.slaState")).isEqualTo("ON_TRACK");
        }

        @Test
        void caseE83FourTimersAtTheirFractions() {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            assertThat(sqlCount("""
                    SELECT count(*) FROM {shard}.case_timer t JOIN {shard}.cases c ON c.case_id = t.case_id
                     WHERE t.case_id = ? AND t.fired_at IS NULL AND t.cancelled_at IS NULL
                       AND abs(extract(epoch FROM (t.due_at - (c.sla_started_at + (c.sla_due_at - c.sla_started_at) *
                           CASE t.kind WHEN 'WARN_50' THEN 0.5 WHEN 'WARN_75' THEN 0.75 WHEN 'WARN_90' THEN 0.9 ELSE 1 END)))) < 1""", caseId))
                    .isEqualTo(4);
        }
    }

    // ============================================================================================ poller

    @Nested
    class Poller {

        /** CASE-E90/E91: timer kind, expected activity type, expected event type, expected threshold detail. */
        static Stream<Arguments> firing() {
            return Stream.of(
                    argumentSet("CASE-E90 50% warning", "WARN_50", "SLA_WARNING", "case.sla.warning", "50"),
                    argumentSet("CASE-E90b 90% warning", "WARN_90", "SLA_WARNING", "case.sla.warning", "90"),
                    argumentSet("CASE-E91 breach", "BREACH", "SLA_BREACHED", "case.sla.breached", "100"));
        }

        @ParameterizedTest
        @MethodSource("firing")
        void dueTimersFireOnce(String kind, String activity, String event, String threshold) {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            forceTimersDue(caseId, kind);
            poller.runOnce();
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_activity a JOIN {shard}.case_activity_detail d "
                    + "ON d.case_id = a.case_id AND d.activity_id = a.activity_id "
                    + "WHERE a.case_id = ? AND a.activity_type = '" + activity + "' AND d.field_name = 'threshold' AND d.new_value = '" + threshold + "'", caseId))
                    .isEqualTo(1);
            assertThat(sqlCount("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = '" + event + "'", caseId))
                    .isEqualTo(1);
        }

        @Test
        void caseE91BreachedCaseReportsBreachedState() throws Exception {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            owner.sql("UPDATE %s.cases SET sla_due_at = now() - interval '1 minute' WHERE case_id = ?"
                    .formatted(shardOf(caseId).schemaName())).param(numericId(caseId)).update();
            assertThat((String) JsonPath.read(json(CARE_IN, caseId), "$.slaState")).isEqualTo("BREACHED");
        }

        @Test
        void caseE92ConcurrentPollersFireExactlyOnce() throws Exception {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            forceTimersDue(caseId, null);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<Integer> run = poller::runOnce;
                pool.invokeAll(List.of(run, run)).forEach(f -> { try { f.get(); } catch (Exception e) { throw new IllegalStateException(e); } });
            }
            poller.runOnce();
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type IN ('SLA_WARNING','SLA_BREACHED')", caseId))
                    .isEqualTo(4);
            assertThat(sqlCount("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type LIKE 'case.sla.%'", caseId))
                    .isEqualTo(4);
        }

        @Test
        void caseE93ClosingCancelsTimers() throws Exception {
            var caseId = createCaseOk(GROWER_IN1, advice("70001", "80001"));
            transition(CARE_IN, caseId, 0, "IN_PROGRESS");
            transition(CARE_IN, caseId, 1, "ADVICE_GIVEN");
            transition(CARE_IN, caseId, 2, "CLOSED");
            forceTimersDue(caseId, null);
            poller.runOnce();
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type LIKE 'SLA_%'", caseId)).isZero();
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_timer WHERE case_id = ? AND cancelled_at IS NOT NULL", caseId)).isEqualTo(4);
        }

        @Test
        void caseE94ReopenRearmsTheSla() throws Exception {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            forceStatus(caseId, "RESOLVED_BY_ADVICE", false);
            transition(CARE_IN, caseId, 0, "CLOSED");
            owner.sql("UPDATE %s.cases SET sla_started_at = now() - interval '30 days', sla_due_at = now() - interval '20 days' WHERE case_id = ?"
                    .formatted(shardOf(caseId).schemaName())).param(numericId(caseId)).update();
            transition(CARE_IN, caseId, 1, "REOPENED");
            var json = json(CARE_IN, caseId);
            Instant due = Instant.parse(JsonPath.read(json, "$.slaDueAt"));
            assertThat(Duration.between(Instant.now(), due).minusHours(240).abs()).isLessThan(Duration.ofMinutes(2));
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_timer WHERE case_id = ? AND fired_at IS NULL AND cancelled_at IS NULL", caseId))
                    .isEqualTo(4);
        }
    }

    // ============================================================================================ SLA state + filters

    @Nested
    class SlaStateAndFilters {

        /** CASE-E95: started (hours ago), due (hours from now, negative = past), closed (hours ago, null = open), expected. */
        static Stream<Arguments> states() {
            return Stream.of(
                    argumentSet("CASE-E95a 10% elapsed", 10, 90, null, "ON_TRACK"),
                    argumentSet("CASE-E95b 80% elapsed", 80, 20, null, "AT_RISK"),
                    argumentSet("CASE-E95c past due", 120, -20, null, "BREACHED"),
                    argumentSet("CASE-E95d closed before due", 50, 50, 10, "MET"),
                    argumentSet("CASE-E95e closed after due", 120, -20, 1, "MISSED"));
        }

        @ParameterizedTest
        @MethodSource("states")
        void slaStateOnViewAndFilter(int startedHoursAgo, int dueInHours, Integer closedHoursAgo, String expected) throws Exception {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            owner.sql(("UPDATE %s.cases SET sla_started_at = now() - make_interval(hours => ?), sla_due_at = now() + make_interval(hours => ?),"
                            + " closed_at = %s WHERE case_id = ?").formatted(shardOf(caseId).schemaName(),
                            closedHoursAgo == null ? "NULL" : "now() - make_interval(hours => " + closedHoursAgo + ")"))
                    .params(startedHoursAgo, dueInHours, numericId(caseId)).update();
            assertThat((String) JsonPath.read(json(CARE_IN, caseId), "$.slaState")).isEqualTo(expected);
            var listed = as(CARE_IN, get("/cases").param("slaState", expected).param("territoryId", "1002").param("limit", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(listed, "$.items[*].caseId")).contains(caseId);
            assertThat(JsonPath.<List<String>>read(listed, "$.items[*].slaState")).containsOnly(expected);
        }

        @Test
        void caseE96BreachedFilterRespectsScope() throws Exception {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            owner.sql("UPDATE %s.cases SET sla_due_at = now() - interval '1 hour' WHERE case_id = ?"
                    .formatted(shardOf(caseId).schemaName())).param(numericId(caseId)).update();
            var br = as(CARE_BR, get("/cases").param("slaState", "BREACHED").param("limit", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(br, "$.items[*].caseId")).doesNotContain(caseId);
        }

        @Test
        void caseE97QueueFilter() throws Exception {
            var caseId = createCaseOk(GROWER_IN2, with(complaint(), "growerId", "70002", "farmId", "80002", "caseType", "ADVERSE_INCIDENT"));
            var listed = as(CARE_IN, get("/cases").param("queue", "IN-STEWARDSHIP").param("limit", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(listed, "$.items[*].caseId")).contains(caseId);
            assertThat(JsonPath.<List<String>>read(listed, "$.items[*].queueCode")).containsOnly("IN-STEWARDSHIP");
            problem(as(CARE_IN, get("/cases").param("queue", "IN-NOPE")), 400, "VALIDATION_FAILED")
                    .andExpect(jsonPath("$.errors[0].field").value("queue"));
        }
    }

    // ============================================================================================ routing

    @Nested
    class Routing {

        /** CASE-E84…E89: actor, body, expected queue. */
        static Stream<Arguments> routes() {
            return Stream.of(
                    argumentSet("CASE-E84 adverse incident → stewardship", GROWER_IN1, with(complaint(), "caseType", "ADVERSE_INCIDENT"), "IN-STEWARDSHIP"),
                    argumentSet("CASE-E85 seeds complaint → seeds quality", GROWER_IN2,
                            with(complaint(), "growerId", "70002", "farmId", "80002", "productId", "9003", "batchNo", "HYC2605-009"), "IN-SEEDS-QUALITY"),
                    argumentSet("CASE-E86 Pune complaint → Maharashtra field (ancestor)", GROWER_IN1, complaint(), "IN-MH-FIELD"),
                    argumentSet("CASE-E87 Karnataka complaint → CP complaints", CARE_IN, with(complaint(), "growerId", "70004", "farmId", "80004"), "IN-CP-COMPLAINTS"),
                    argumentSet("CASE-E88 Karnataka advice → fallback", CARE_IN, advice("70004", "80004"), "IN-CARE"),
                    argumentSet("CASE-E89 Brazil complaint → BR fallback", GROWER_BR1,
                            with(complaint(), "growerId", "70003", "farmId", "80003"), "BR-CARE"));
        }

        @ParameterizedTest
        @MethodSource("routes")
        void routedToQueue(GcPrincipal actor, Map<String, Object> body, String queue) throws Exception {
            var json = create(actor, body, UUID.randomUUID()).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.queueCode").value(queue))
                    .andReturn().getResponse().getContentAsString();
            String caseId = JsonPath.read(json, "$.caseId");
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'ROUTED'", caseId)).isEqualTo(1);
            assertThat(sqlCount("SELECT count(*) FROM {shard}.outbox WHERE aggregate_id = ?::text AND event_type = 'case.routed'", caseId)).isEqualTo(1);
        }
    }

    // ============================================================================================ maintenance jobs

    @Nested
    class Maintenance {

        @Test
        void caseE98PartitionJobIsIdempotent() {
            maintenance.ensurePartitions(8);
            assertThat(maintenance.ensurePartitions(8)).isZero();
        }

        @Test
        void caseE99IdempotencyKeysExpireAfterSevenDays() {
            var oldCase = createCaseOk(GROWER_IN1, complaint());
            var newCase = createCaseOk(GROWER_IN1, complaint());
            owner.sql("UPDATE %s.case_idempotency SET created_at = now() - interval '8 days' WHERE case_id = ?"
                    .formatted(shardOf(oldCase).schemaName())).param(numericId(oldCase)).update();
            owner.sql("UPDATE %s.case_idempotency SET created_at = now() - interval '1 day' WHERE case_id = ?"
                    .formatted(shardOf(newCase).schemaName())).param(numericId(newCase)).update();
            maintenance.cleanupIdempotency(Duration.ofDays(7));
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_idempotency WHERE case_id = ?", oldCase)).isZero();
            assertThat(sqlCount("SELECT count(*) FROM {shard}.case_idempotency WHERE case_id = ?", newCase)).isEqualTo(1);
        }

        @Test
        void caseE100RowsInADefaultPartitionAreReported() {
            var caseId = createCaseOk(GROWER_IN1, complaint());
            var shard = shardOf(caseId).schemaName();
            long strayId = IdCodec.encode(Instant.parse("2026-01-02T00:00:00Z"), 46, shardOf(caseId).shardNo(), 1);   // no weekly partition in January
            owner.sql(("INSERT INTO %s.cases (case_id, case_type_code, status_code, territory_id, bu_code, grower_id, "
                    + "raised_by_user_id, raised_channel, title, description) "
                    + "VALUES (?, 'AGRONOMY_ADVICE', 'SUBMITTED', 1002, 'CP', 70001, 1, 'APP', 'stray', 'stray')").formatted(shard))
                    .param(strayId).update();
            owner.sql(("INSERT INTO %s.case_timer (case_id, kind, due_at) VALUES (?, 'BREACH', now() + interval '1 day')").formatted(shard))
                    .param(strayId).update();
            try {
                scheduledJobs.checkDefaultPartitions();
                assertThat(meters.get("gc.partition.default.rows").gauge().value()).isGreaterThanOrEqualTo(2);
            } finally {
                owner.sql("DELETE FROM %s.case_timer WHERE case_id = ?".formatted(shard)).param(strayId).update();
                owner.sql("DELETE FROM %s.cases WHERE case_id = ?".formatted(shard)).param(strayId).update();
            }
            scheduledJobs.checkDefaultPartitions();
            assertThat(meters.get("gc.partition.default.rows").gauge().value()).isZero();
        }

        @Test
        void caseE101ConcurrentPartitionJobsProcessEachShardOnce() throws Exception {
            int before = partitionCount();
            int created;
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<Integer> run = () -> maintenance.ensurePartitions(16);
                created = pool.invokeAll(List.of(run, run)).stream().mapToInt(f -> {
                    try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); }
                }).sum();
            }
            assertThat(partitionCount() - before).isEqualTo(created);
            assertThat(maintenance.ensurePartitions(16)).isZero();
        }

        int partitionCount() {
            // tables only: pg_inherits also links the index partitions created with each table partition
            return owner.sql("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE c.relkind = 'r'")
                    .query(Integer.class).single();
        }
    }

    private String createCaseOk(GcPrincipal actor, Map<String, Object> body) {
        try {
            return createCase(actor, body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
