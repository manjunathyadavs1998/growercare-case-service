package com.growercare.casesvc.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.adapters.in.messaging.GrowerEventsListener;
import com.growercare.casesvc.application.port.GrowerDirectory.GrowerReplica;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.testsupport.KafkaTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SPEC-CASE-06 R130–R137 / E130–E138: case-service keeps {@code ref.grower} in step with grower-service from
 * {@code grower.grower.events} (fetch per event, version guard, inbox, own DLQ). Real Kafka and Postgres; grower-service
 * is the stubbed {@code GrowerDirectory}. Every row uses its own grower id, event id and key.
 */
class GrowerReplicaConsumerIT extends CaseApiTestBase {

    static final String TOPIC = "grower.grower.events";
    static final String DLQ = "dlq.case-service.grower-replica";

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KafkaTestContainer::bootstrapServers);
        r.add("gc.events.consumers-enabled", () -> "true");
        r.add("gc.events.retry-initial-backoff", () -> "50ms");
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    static String event(UUID eventId, String type, String growerId) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","data":{"growerId":"%s","countryCode":"IN"}}"""
                .formatted(eventId, type, Instant.now(), growerId);
    }

    static GrowerReplica grower(long id, long territory, Long partner, int version) {
        return new GrowerReplica(id, "IN", territory, null, "Replica Test", new byte[32], "4321", partner, "ACTIVE", version);
    }

    void send(String key, String value) throws Exception {
        kafka.send(TOPIC, key, value).get();
    }

    int inboxRows(UUID eventId) {
        return owner.sql("SELECT count(*) FROM msg.inbox WHERE consumer_group = ? AND event_id = ?")
                .params(GrowerEventsListener.GROUP, eventId).query(Integer.class).single();
    }

    void awaitInbox(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> inboxRows(eventId) == 1);
    }

    record Row(long territoryId, Long partner, int version, String status) { }

    Row replica(long growerId) {
        return owner.sql("SELECT territory_id, preferred_partner_org_id, version, status FROM ref.grower WHERE grower_id = ?")
                .param(growerId).query((rs, n) -> new Row(rs.getLong(1), rs.getObject(2, Long.class), rs.getInt(3), rs.getString(4)))
                .optional().orElse(null);
    }

    // ------------------------------------------------------------------------------------------ R130 / E130, E131

    @ParameterizedTest(name = "CASE-E130/E131 {0} upserts the replica from the fetched state")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"grower.registered", "grower.updated"})
    void eventsUpsertTheReplica(String type) throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        growerDirectory.put(grower(id, 1003, 502L, 0));
        var eventId = UUID.randomUUID();
        send(Long.toString(id), event(eventId, type, Long.toString(id)));
        awaitInbox(eventId);
        assertThat(replica(id)).isEqualTo(new Row(1003, 502L, 0, "ACTIVE"));

        growerDirectory.put(grower(id, 1002, null, 3));           // grower-service moved it and cleared the partner
        var second = UUID.randomUUID();
        send(Long.toString(id), event(second, "grower.updated", Long.toString(id)));
        awaitInbox(second);
        assertThat(replica(id)).isEqualTo(new Row(1002, null, 3, "ACTIVE"));
    }

    // ------------------------------------------------------------------------------------------ R131 / E132

    @Test
    void CASE_E132_anOlderFetchNeverOverwritesANewerRow() throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        growerDirectory.put(grower(id, 1003, null, 5));
        var first = UUID.randomUUID();
        send(Long.toString(id), event(first, "grower.registered", Long.toString(id)));
        awaitInbox(first);
        growerDirectory.put(grower(id, 1002, null, 4));            // a stale answer
        var stale = UUID.randomUUID();
        send(Long.toString(id), event(stale, "grower.updated", Long.toString(id)));
        awaitInbox(stale);
        assertThat(replica(id)).isEqualTo(new Row(1003, null, 5, "ACTIVE"));
    }

    // ------------------------------------------------------------------------------------------ R132 / E133

    @Test
    void CASE_E133_aDuplicateDeliveryFetchesOnce() throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        growerDirectory.put(grower(id, 1003, null, 0));
        var eventId = UUID.randomUUID();
        var marker = UUID.randomUUID();
        var key = Long.toString(id);
        send(key, event(eventId, "grower.registered", key));
        send(key, event(eventId, "grower.registered", key));     // redelivery of the same event
        send(key, event(marker, "grower.plot.changed", key));     // same partition, consumed after both
        awaitInbox(marker);
        assertThat(growerDirectory.calls(id)).isEqualTo(1);
        assertThat(inboxRows(eventId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ R133 / E134, R136 / E137

    @Test
    void CASE_E134_aGrowerUnknownToGrowerServiceIsIgnored() throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");   // never put into the stub
        var eventId = UUID.randomUUID();
        send(Long.toString(id), event(eventId, "grower.registered", Long.toString(id)));
        awaitInbox(eventId);
        assertThat(replica(id)).isNull();
    }

    @Test
    void CASE_E137_unknownEventTypesAreIgnoredButRecorded() throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        growerDirectory.put(grower(id, 1003, null, 0));
        var eventId = UUID.randomUUID();
        send(Long.toString(id), event(eventId, "grower.plot.changed", Long.toString(id)));
        awaitInbox(eventId);
        assertThat(growerDirectory.calls(id)).isZero();
        assertThat(replica(id)).isNull();
    }

    // ------------------------------------------------------------------------------------------ R134 / E135, R135 / E136

    /** a value for a fresh grower id; how the stub is set up for that id; expected exception class fragment; attempts */
    static Stream<Arguments> poison() {
        return Stream.of(
                argumentSet("CASE-E135 grower-service down", (LongFunction<String>) id -> "down", "DependencyUnavailableException", 4),
                argumentSet("CASE-E135 territory unknown to case-service", (LongFunction<String>) id -> "territory", "DependencyUnavailableException", 4),
                argumentSet("CASE-E135 partner unknown to case-service", (LongFunction<String>) id -> "partner", "DependencyUnavailableException", 4),
                argumentSet("CASE-E136 not JSON", (LongFunction<String>) id -> "value:not-json", "MalformedEventException", 0),
                argumentSet("CASE-E136 envelope without eventId", (LongFunction<String>) id -> "value:{\"eventType\":\"grower.registered\",\"data\":{\"growerId\":\"" + id + "\"}}",
                        "MalformedEventException", 0),
                argumentSet("CASE-E136 growerId is not an id", (LongFunction<String>) id -> "value:" + event(UUID.randomUUID(), "grower.registered", "abc"),
                        "MalformedEventException", 0));
    }

    @ParameterizedTest
    @MethodSource("poison")
    void poisonRecordsGoToTheirOwnDlqAndThePartitionMovesOn(LongFunction<String> setup, String exceptionClass, int attempts) throws Exception {
        long id = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        var mode = setup.apply(id);
        String value;
        if (mode.startsWith("value:")) {
            value = mode.substring("value:".length());
        } else {
            switch (mode) {
                case "down" -> growerDirectory.failFor(id);
                case "territory" -> growerDirectory.put(grower(id, 9999, null, 0));
                default -> growerDirectory.put(grower(id, 1003, 999L, 0));
            }
            value = event(UUID.randomUUID(), "grower.registered", Long.toString(id));
        }
        var key = "poison-" + UUID.randomUUID();
        long nextId = CaseTestDatabase.nextGrowerIdInTestShards("IN");
        growerDirectory.put(grower(nextId, 1003, null, 0));
        var next = UUID.randomUUID();

        try (var dlq = dlqConsumer(DLQ)) {
            send(key, value);
            send(key, event(next, "grower.registered", Long.toString(nextId)));   // same key ⇒ same partition, after it

            var dead = awaitDlqRecord(dlq, key);
            assertThat(dead.value()).isEqualTo(value);
            assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).endsWith(exceptionClass);
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
            awaitInbox(next);
            if (attempts > 0) {
                assertThat(growerDirectory.calls(id)).isEqualTo(attempts);          // 1 try + 3 retries
            }
            assertThat(replica(id)).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------ R137 / E138

    @Test
    void CASE_E138_eachConsumerHasItsOwnDlq() throws Exception {
        var growerKey = "e138-grower-" + UUID.randomUUID();
        var grantKey = "e138-grant-" + UUID.randomUUID();
        try (var growerDlq = dlqConsumer(DLQ); var grantDlq = dlqConsumer("dlq.case-service.iam-grant-cache")) {
            send(growerKey, "not-json");
            kafka.send("iam.grant.events", grantKey, "not-json").get();
            assertThat(awaitDlqRecord(growerDlq, growerKey).value()).isEqualTo("not-json");
            assertThat(awaitDlqRecord(grantDlq, grantKey).value()).isEqualTo("not-json");
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static KafkaConsumer<String, String> dlqConsumer(String topic) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static ConsumerRecord<String, String> awaitDlqRecord(KafkaConsumer<String, String> dlq, String key) {
        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            dlq.poll(Duration.ofMillis(500)).forEach(seen::add);
            return seen.stream().anyMatch(r -> key.equals(r.key()));
        });
        return seen.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
