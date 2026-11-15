package com.growercare.casesvc.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.shard.CaseNumber;
import com.growercare.shard.Damm;
import com.growercare.shard.IdCodec;
import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.testsupport.PostgresTestDatabase;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the sharded case schema against a real PostgreSQL 17, under the production privilege split
 * (owner migrates, app role runs). Increment 1 acceptance tests.
 */
class CaseSchemaMigrationIT {

    private static PostgresTestDatabase db;
    private static JdbcClient app;
    private static JdbcClient owner;

    @BeforeAll
    static void migrate() {
        db = CaseTestDatabase.migrated();       // migrated once per JVM, shared with the other case ITs
        app = JdbcClient.create(db.appDataSource());
        owner = JdbcClient.create(db.ownerDataSource());
    }

    @Test
    void migratesCommonSchemaAndEverySelectedShardSchema() {
        int schemasWithCases = owner.sql("""
                SELECT count(*) FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid
                 WHERE c.relname = 'cases'""").query(Integer.class).single();
        assertThat(schemasWithCases).isEqualTo(CaseTestDatabase.TEST_SHARDS.size());   // light test DB (production: 128)
        int appliedShardMigrations = owner.sql("""
                SELECT count(*) FROM pg_namespace n
                 WHERE n.nspname ~ '^(in|br)_s[0-9]{2}$'
                   AND to_regclass(n.nspname || '.flyway_schema_history') IS NOT NULL""").query(Integer.class).single();
        assertThat(appliedShardMigrations).isEqualTo(CaseTestDatabase.TEST_SHARDS.size());
    }

    @Test
    void rerunningMigrationIsANoOp() {
        var second = CaseTestDatabase.migrator(db).migrate();
        assertThat(second.shardCount()).isEqualTo(CaseTestDatabase.TEST_SHARDS.size());
        assertThat(second.commonMigrations()).isZero();
        assertThat(second.shardMigrations()).isZero();
    }

    @Nested
    class Identifiers {

        @Test
        void databaseGeneratedIdsDecodeToCountryShardAndTime() {
            var before = Instant.now().minusSeconds(5);
            var row = insertCase("in_s27", "PRODUCT_COMPLAINT", "SUBMITTED", 70001L, 1002L);
            var decoded = IdCodec.decode(row.caseId());

            assertThat(decoded.countryIx()).isEqualTo(46);
            assertThat(decoded.shardNo()).isEqualTo(27);
            assertThat(decoded.createdAt()).isBetween(before, Instant.now().plusSeconds(5));

            var caseNo = CaseNumber.parse(row.caseNo()).orElseThrow();
            assertThat(caseNo.caseNoCc()).isEqualTo(71);
            assertThat(caseNo.shardNo()).isEqualTo(27);
        }

        @Test
        void idsAreUniqueAndIncreasingWithinAShard() {
            var ids = IntStream.range(0, 200)
                    .mapToObj(i -> insertCase("br_s05", "AGRONOMY_ADVICE", "SUBMITTED", 70003L, 2002L).caseId())
                    .toList();
            assertThat(new HashSet<>(ids)).hasSize(200);
            assertThat(ids).isSorted();
            assertThat(IdCodec.decode(ids.getFirst()).countryIx()).isEqualTo(8);
        }

        @Test
        void sqlAndJavaDammAndIdFloorAgree() {
            var random = ThreadLocalRandom.current();
            IntStream.range(0, 300).forEach(i -> {
                var digits = Long.toString(random.nextLong(1_000_000_000_000L, 9_999_999_999_999L));
                var sql = app.sql("SELECT ref.damm_check_digit(?)").param(digits).query(String.class).single();
                assertThat(Integer.parseInt(sql)).as(digits).isEqualTo(Damm.checkDigit(digits));
            });
            var instant = Instant.parse("2026-11-15T08:30:00.250Z");
            long sqlFloor = app.sql("SELECT ref.id_floor(?::timestamptz)").param(instant.toString()).query(Long.class).single();
            assertThat(sqlFloor).isEqualTo(IdCodec.floor(instant));
        }
    }

    @Nested
    class Partitioning {

        @Test
        void newRowsLandInTheCurrentWeeklyPartitionNotTheDefault() {
            var row = insertCase("in_s01", "AGRONOMY_ADVICE", "SUBMITTED", 70001L, 1002L);
            var partition = app.sql("SELECT tableoid::regclass::text FROM in_s01.cases WHERE case_id = ?")
                    .param(row.caseId()).query(String.class).single();
            assertThat(partition).matches("in_s01\\.cases_w\\d{8}");
        }

        @Test
        void appRoleCanExtendPartitionsAndTheFunctionIsIdempotent() {
            int created = app.sql("SELECT in_s02.ensure_week_partitions(1, 12)").query(Integer.class).single();
            assertThat(created).isEqualTo(4 * 7);                         // weeks 9..12 × 7 case-keyed tables
            int again = app.sql("SELECT in_s02.ensure_week_partitions(1, 12)").query(Integer.class).single();
            assertThat(again).isZero();
        }
    }

    @Nested
    class Integrity {

        @Test
        void statusMustBelongToTheCaseType() {
            assertThatThrownBy(() -> insertCase("in_s03", "AGRONOMY_ADVICE", "LAB_ANALYSIS", 70001L, 1002L))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cases_status_fk");
        }

        @Test
        void growerMustBelongToTheShardCountry() {
            assertThatThrownBy(() -> insertCase("in_s03", "AGRONOMY_ADVICE", "SUBMITTED", 70003L /* BR grower */, 1002L))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cases_grower_fk");
        }

        @Test
        void territoryMustBelongToTheShardCountry() {
            assertThatThrownBy(() -> insertCase("in_s03", "AGRONOMY_ADVICE", "SUBMITTED", 70001L, 2002L /* BR */))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cases_territory_fk");
        }

        @Test
        void commentsAreIdempotentPerClientMessageId() {
            var row = insertCase("in_s04", "PRODUCT_COMPLAINT", "SUBMITTED", 70001L, 1002L);
            var msgId = UUID.randomUUID();
            postComment("in_s04", row.caseId(), msgId);
            // On a partitioned table PostgreSQL names the PARTITION's index (e.g. case_comment_w20261109_case_id_client_msg_id_key),
            // not the parent constraint, so assert the SQLState-derived type and the key columns instead.
            assertThatThrownBy(() -> postComment("in_s04", row.caseId(), msgId))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("client_msg_id");

            // The repository pattern: ON CONFLICT … DO NOTHING turns a retry into a no-op instead of an exception.
            int inserted = app.sql("""
                    INSERT INTO in_s04.case_comment (case_id, author_user_id, author_role, visibility, body, client_msg_id)
                    VALUES (?, 101, 'CARE_AGENT', 'PUBLIC', 'Retry of the same message', ?)
                    ON CONFLICT (case_id, client_msg_id) DO NOTHING""").params(row.caseId(), msgId).update();
            assertThat(inserted).isZero();
        }

        @Test
        void activityStatusFieldsMatchTheActivityType() {
            var row = insertCase("in_s04", "PRODUCT_COMPLAINT", "SUBMITTED", 70001L, 1002L);
            assertThatThrownBy(() -> app.sql("""
                    INSERT INTO in_s04.case_activity (case_id, activity_type, actor_user_id, from_status, to_status)
                    VALUES (?, 'STATUS_CHANGED', 101, 'TRIAGED', 'TRIAGED')""").param(row.caseId()).update())
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("case_activity_status_fields");
        }
    }

    @Nested
    class LeastPrivilege {

        @Test
        void appRoleCannotRunDdl() {
            assertThatThrownBy(() -> app.sql("CREATE TABLE in_s05.evil (id int)").update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }

        @Test
        void appRoleCannotHardDeleteCaseHistory() {
            assertThatThrownBy(() -> app.sql("DELETE FROM in_s05.cases").update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }

        @Test
        void appRoleCannotWriteReferenceData() {
            assertThatThrownBy(() -> app.sql("UPDATE ref.country SET active = false").update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Nested
    class WorkflowDefinition {

        record Transition(String type, String from, String to) { }

        @Test
        void everyCaseTypeHasOneInitialStatusAndEveryStatusIsReachableAndCanProgress() {
            var types = app.sql("SELECT case_type_code FROM ref.case_type").query(String.class).list();
            var transitions = app.sql("SELECT case_type_code, from_status, to_status FROM ref.case_transition")
                    .query((rs, n) -> new Transition(rs.getString(1), rs.getString(2), rs.getString(3))).list();

            assertThat(types).hasSize(4).allSatisfy(type -> {
                var initial = app.sql("SELECT status_code FROM ref.case_status WHERE case_type_code = ? AND is_initial")
                        .param(type).query(String.class).list();
                assertThat(initial).as("initial status of %s", type).hasSize(1);

                var edges = transitions.stream().filter(t -> t.type().equals(type))
                        .collect(Collectors.groupingBy(Transition::from, Collectors.mapping(Transition::to, Collectors.toSet())));
                var statuses = Set.copyOf(app.sql("SELECT status_code FROM ref.case_status WHERE case_type_code = ?")
                        .param(type).query(String.class).list());

                assertThat(reachable(initial.getFirst(), edges)).as("reachable statuses of %s", type).isEqualTo(statuses);
                var closed = Set.copyOf(app.sql("SELECT status_code FROM ref.case_status WHERE case_type_code = ? AND is_closed")
                        .param(type).query(String.class).list());
                var stuck = statuses.stream().filter(s -> !edges.containsKey(s) && !closed.contains(s)).toList();
                assertThat(stuck).as("open statuses of %s with no way forward", type).isEmpty();
                assertThat(closed).as("closed statuses of %s", type).isNotEmpty();
            });
        }

        private static Set<String> reachable(String start, Map<String, Set<String>> edges) {
            var seen = new HashSet<String>(Set.of(start));
            var queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                edges.getOrDefault(queue.poll(), Set.of()).stream().filter(seen::add).forEach(queue::add);
            }
            return seen;
        }
    }

    // ---------------------------------------------------------------- helpers

    record CaseRow(long caseId, String caseNo) { }

    private static CaseRow insertCase(String schema, String type, String status, long growerId, long territoryId) {
        // Test-only SQL; schema names here are literals from the test, never input.
        return app.sql("""
                INSERT INTO %s.cases (case_type_code, status_code, territory_id, bu_code, grower_id,
                                      raised_by_user_id, raised_channel, title, description)
                VALUES (:type, :status, :territory, 'CP', :grower, 1, 'APP', 'Leaves yellowing', 'Spots after spray')
                RETURNING case_id, case_no""".formatted(schema))
                .param("type", type).param("status", status).param("territory", territoryId).param("grower", growerId)
                .query((rs, n) -> new CaseRow(rs.getLong("case_id"), rs.getString("case_no")))
                .single();
    }

    private static void postComment(String schema, long caseId, UUID clientMsgId) {
        app.sql("""
                INSERT INTO %s.case_comment (case_id, author_user_id, author_role, visibility, body, client_msg_id)
                VALUES (?, 101, 'CARE_AGENT', 'PUBLIC', 'We are checking the batch.', ?)""".formatted(schema))
                .params(caseId, clientMsgId).update();
    }
}
