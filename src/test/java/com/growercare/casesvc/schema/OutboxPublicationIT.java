package com.growercare.casesvc.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.outbox.OutboxWriter;
import com.growercare.shard.ShardId;
import com.growercare.testsupport.PostgresTestDatabase;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-PLAT-04 R51–R53, R62 for gc_case: the Debezium publication streams outbox tables (+ heartbeat) only, and the
 * CDC role can read nothing else.
 */
class OutboxPublicationIT {

    private static final PostgresTestDatabase DB = CaseTestDatabase.migrated();
    private final JdbcClient owner = JdbcClient.create(DB.ownerDataSource());
    private final org.springframework.jdbc.core.JdbcTemplate cdc = new org.springframework.jdbc.core.JdbcTemplate(DB.cdcDataSource());   // SELECT and DML alike

    @Test
    void platE60PublicationContainsOnlyOutboxTablesAndTheHeartbeat() {
        var expected = Stream.concat(
                        CaseTestDatabase.TEST_SHARDS.stream().map(s -> s.schemaName() + ".outbox"),
                        Stream.of("msg.cdc_heartbeat"))
                .collect(Collectors.toSet());

        var published = owner.sql("SELECT schemaname || '.' || tablename FROM pg_publication_tables WHERE pubname = 'gc_outbox'")
                .query(String.class).set();
        var operations = owner.sql("SELECT pubinsert, pubupdate, pubdelete, pubtruncate FROM pg_publication WHERE pubname = 'gc_outbox'")
                .query((rs, n) -> Map.of("insert", rs.getBoolean(1), "update", rs.getBoolean(2),
                        "delete", rs.getBoolean(3), "truncate", rs.getBoolean(4)))
                .single();

        assertThat(published).isEqualTo(expected);
        assertThat(operations).isEqualTo(Map.of("insert", true, "update", true, "delete", false, "truncate", false));
    }

    /** statement run as gc_cdc, whether it is allowed. */
    static Stream<Arguments> cdcPrivileges() {
        return Stream.of(
                argumentSet("PLAT-E62a outbox is readable", "SELECT count(*) FROM in_s01.outbox", true),
                argumentSet("PLAT-E62b cases are not", "SELECT count(*) FROM in_s01.cases", false),
                argumentSet("PLAT-E62c comments are not", "SELECT count(*) FROM in_s01.case_comment", false),
                argumentSet("PLAT-E62d grower master data is not", "SELECT count(*) FROM ref.grower", false),
                argumentSet("PLAT-E76a heartbeat upsert is allowed", """
                        INSERT INTO msg.cdc_heartbeat (id, beat_at) VALUES (1, now())
                        ON CONFLICT (id) DO UPDATE SET beat_at = EXCLUDED.beat_at""", true),
                argumentSet("PLAT-E76b writing the outbox is not", """
                        INSERT INTO in_s01.outbox (event_id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (gen_random_uuid(), 'case', '1', 'case.created', '{}')""", false),
                argumentSet("PLAT-E76c inbox is not readable", "SELECT count(*) FROM msg.inbox", false));
    }

    @ParameterizedTest
    @MethodSource("cdcPrivileges")
    void cdcRoleSeesOutboxTablesOnly(String sql, boolean allowed) {
        if (allowed) {
            cdc.execute(sql);
        } else {
            assertThatThrownBy(() -> cdc.execute(sql))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageStartingWith(rootCauseMessage(sql));
        }
    }

    /** Postgres names the first object it refuses: the schema for ref.*, otherwise the table. */
    private static String rootCauseMessage(String sql) {
        if (sql.contains("ref.")) {
            return "ERROR: permission denied for schema ref";
        }
        var table = sql.replaceAll("(?s).*?(?:FROM|INTO)\\s+[a-z_0-9]+\\.([a-z_]+).*", "$1");
        return "ERROR: permission denied for table " + table;
    }

    /** PLAT-R51 / E73: committed → one outbox row; rolled back → none (so Debezium has nothing to publish). */
    static Stream<Arguments> transactions() {
        return Stream.of(
                argumentSet("PLAT-E73a committed business transaction", true, 1),
                argumentSet("PLAT-E73b rolled-back business transaction", false, 0));
    }

    @ParameterizedTest
    @MethodSource("transactions")
    void outboxRowExistsOnlyIfTheTransactionCommits(boolean commit, int expectedRows) {
        var dataSource = DB.appDataSource();                     // one instance: the client must join the transaction
        var app = JdbcClient.create(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var writer = new OutboxWriter(app, JsonMapper.builder().build());
        var shard = new ShardId("IN", 1);
        var eventId = new UUID[1];

        try {
            tx.executeWithoutResult(status -> {
                eventId[0] = writer.append(shard.schemaName() + ".outbox",
                        new OutboxWriter.Event("case", "42", "case.routed", Map.of("caseId", "42", "queueCode", "IN-QA")), null);
                if (!commit) {
                    throw new IllegalStateException("business rule failed after the outbox insert");
                }
            });
        } catch (IllegalStateException expected) {
            // the rollback case
        }

        assertThat(owner.sql("SELECT count(*) FROM in_s01.outbox WHERE event_id = ?").param(eventId[0])
                .query(Integer.class).single()).isEqualTo(expectedRows);
    }
}
