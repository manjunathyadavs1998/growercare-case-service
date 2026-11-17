package com.growercare.casesvc.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.growercare.casesvc.adapters.in.messaging.ReferenceEventsListener;
import com.growercare.casesvc.application.port.CatalogDirectory.BatchReplica;
import com.growercare.casesvc.application.port.CatalogDirectory.ProductReplica;
import com.growercare.casesvc.application.port.CatalogDirectory.RegistrationReplica;
import com.growercare.casesvc.application.port.FarmDirectory.FarmReplica;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.SeedGrowers;
import com.growercare.casesvc.support.StubReferenceOwners;
import com.growercare.testsupport.KafkaTestContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SPEC-CASE-08 R160–R165 (CASE-E160…E165): farm and catalog events keep the replicas in step. Real Kafka and Postgres;
 * the owners are the stubbed directories. Every row uses its own ids and keys.
 */
class FarmCatalogConsumerIT extends CaseApiTestBase {

    static final String FARMS = "grower.farm.events";
    static final String PRODUCTS = "catalog.product.events";
    static final String BATCHES = "catalog.batch.events";
    static final long G1 = SeedGrowers.G1;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KafkaTestContainer::bootstrapServers);
        r.add("gc.events.consumers-enabled", () -> "true");
        r.add("gc.events.retry-initial-backoff", () -> "50ms");
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    static String event(UUID eventId, String type, String data) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","data":%s}""".formatted(eventId, type, Instant.now(), data);
    }

    static String farmData(long farmId) {
        return "{\"farmId\":\"%d\",\"growerId\":\"%d\",\"version\":0}".formatted(farmId, G1);
    }

    static FarmReplica farm(long farmId, String status, int version) {
        return new FarmReplica(farmId, G1, "Event farm", new BigDecimal("2.00"), null, null, status, version);
    }

    static ProductReplica product(long productId, String status, long registrationVersion) {
        return new ProductReplica(productId, "EV-" + productId, "CP", "Event product", "INSECTICIDE", 1,
                List.of(new RegistrationReplica("IN", "CIR-E-" + productId, status, null, registrationVersion)));
    }

    void send(String topic, String key, String value) throws Exception {
        kafka.send(topic, key, value).get();
    }

    void awaitInbox(String group, UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> owner.sql("SELECT count(*) FROM msg.inbox WHERE consumer_group = ? AND event_id = ?")
                .params(group, eventId).query(Integer.class).single() == 1);
    }

    String farmRow(long farmId) {
        return owner.sql("SELECT status || ':' || version FROM ref.farm WHERE farm_id = ?").param(farmId).query(String.class).optional().orElse(null);
    }

    @Test
    void CASE_E160_E161_farmEventsUpsertAndArchiveAndOlderFetchesNeverWin() throws Exception {
        long farmId = StubReferenceOwners.nextFarmId();
        owners.put(farm(farmId, "ACTIVE", 0));
        var created = UUID.randomUUID();
        send(FARMS, Long.toString(farmId), event(created, "grower.farm.created", farmData(farmId)));
        awaitInbox(ReferenceEventsListener.FARM_GROUP, created);
        assertThat(farmRow(farmId)).isEqualTo("ACTIVE:0");

        owners.put(farm(farmId, "ARCHIVED", 1));
        var archived = UUID.randomUUID();
        send(FARMS, Long.toString(farmId), event(archived, "grower.farm.archived", farmData(farmId)));
        awaitInbox(ReferenceEventsListener.FARM_GROUP, archived);
        assertThat(farmRow(farmId)).isEqualTo("ARCHIVED:1");

        owners.put(farm(farmId, "ACTIVE", 0));                                  // a stale answer
        var stale = UUID.randomUUID();
        send(FARMS, Long.toString(farmId), event(stale, "grower.farm.created", farmData(farmId)));
        awaitInbox(ReferenceEventsListener.FARM_GROUP, stale);
        assertThat(farmRow(farmId)).isEqualTo("ARCHIVED:1");
    }

    @Test
    void CASE_E166_theOwnersFirstAnswerReplacesASeedCopyEvenAtVersion0() throws Exception {
        long g3 = SeedGrowers.G3;
        owners.put(new FarmReplica(80014, g3, "Lakshmi ragi plot (from grower-service)", new BigDecimal("0.80"), null, null, "ACTIVE", 0));
        var eventId = UUID.randomUUID();
        send(FARMS, "80014", event(eventId, "grower.farm.updated", "{\"farmId\":\"80014\",\"growerId\":\"%d\",\"version\":0}".formatted(g3)));
        awaitInbox(ReferenceEventsListener.FARM_GROUP, eventId);
        assertThat(owner.sql("SELECT name || ':' || version FROM ref.farm WHERE farm_id = 80014").query(String.class).single())
                .isEqualTo("Lakshmi ragi plot (from grower-service):0");
    }

    @Test
    void CASE_E162_aRegistrationChangeDecidesWhetherCasesAreAccepted() throws Exception {
        long productId = StubReferenceOwners.nextProductId();
        owners.put(product(productId, "REGISTERED", 1));
        var first = UUID.randomUUID();
        send(PRODUCTS, Long.toString(productId), event(first, "catalog.product.upserted", "{\"productId\":\"" + productId + "\",\"version\":1}"));
        awaitInbox(ReferenceEventsListener.CATALOG_GROUP, first);

        owners.put(product(productId, "SUSPENDED", 2));
        var changed = UUID.randomUUID();
        send(PRODUCTS, Long.toString(productId), event(changed, "catalog.registration.changed",
                "{\"productId\":\"" + productId + "\",\"countryCode\":\"IN\",\"status\":\"SUSPENDED\"}"));
        awaitInbox(ReferenceEventsListener.CATALOG_GROUP, changed);
        assertThat(owner.sql("SELECT status FROM ref.product_registration WHERE product_id = ? AND country_code = 'IN'").param(productId)
                .query(String.class).single()).isEqualTo("SUSPENDED");
        problem(create(com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1,
                with(complaint(), "productId", Long.toString(productId), "batchNo", null), UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION");
    }

    @Test
    void CASE_E163_batchEventsRefreshOnlyReplicatedBatches() throws Exception {
        long productId = StubReferenceOwners.nextProductId();
        owners.put(product(productId, "REGISTERED", 1));
        var seeded = UUID.randomUUID();
        send(PRODUCTS, Long.toString(productId), event(seeded, "catalog.product.upserted", "{\"productId\":\"" + productId + "\",\"version\":1}"));
        awaitInbox(ReferenceEventsListener.CATALOG_GROUP, seeded);
        var known = StubReferenceOwners.nextBatchNo();
        var unknown = StubReferenceOwners.nextBatchNo();
        owner.sql("INSERT INTO ref.product_batch (product_id, batch_no, mfg_date, expiry_date, plant_code) VALUES (?, ?, DATE '2026-01-01', DATE '2027-01-01', 'OLD')")
                .params(productId, known).update();
        owners.put(new BatchReplica(productId, known, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "IN-GOA", 2));
        owners.put(new BatchReplica(productId, unknown, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "IN-GOA", 1));

        var refresh = UUID.randomUUID();
        var ignore = UUID.randomUUID();
        send(BATCHES, Long.toString(productId), event(refresh, "catalog.batch.upserted",
                "{\"productId\":\"%d\",\"batchNo\":\"%s\",\"version\":2}".formatted(productId, known)));
        send(BATCHES, Long.toString(productId), event(ignore, "catalog.batch.upserted",
                "{\"productId\":\"%d\",\"batchNo\":\"%s\",\"version\":1}".formatted(productId, unknown)));
        awaitInbox(ReferenceEventsListener.CATALOG_GROUP, ignore);
        assertThat(owner.sql("SELECT plant_code || ':' || version FROM ref.product_batch WHERE product_id = ? AND batch_no = ?")
                .params(productId, known).query(String.class).single()).isEqualTo("IN-GOA:2");
        assertThat(owners.batchCalls(productId, unknown)).isZero();
    }

    @Test
    void CASE_E164_aFarmTheOwnerDoesNotKnowChangesNothing() throws Exception {
        long farmId = StubReferenceOwners.nextFarmId();                       // never put into the stub
        var eventId = UUID.randomUUID();
        send(FARMS, Long.toString(farmId), event(eventId, "grower.farm.updated", farmData(farmId)));
        awaitInbox(ReferenceEventsListener.FARM_GROUP, eventId);
        assertThat(farmRow(farmId)).isNull();
    }

    @Test
    void CASE_E164_E165_poisonGoesToTheConsumersOwnDlq() throws Exception {
        long farmId = StubReferenceOwners.nextFarmId();
        owners.failFarm(G1, farmId);
        var downKey = "e165-" + UUID.randomUUID();
        var malformedKey = "e164-" + UUID.randomUUID();
        var catalogKey = "e164-catalog-" + UUID.randomUUID();
        try (var farmDlq = dlqConsumer("dlq.case-service.farm-replica"); var catalogDlq = dlqConsumer("dlq.case-service.catalog-replica")) {
            send(FARMS, downKey, event(UUID.randomUUID(), "grower.farm.updated", farmData(farmId)));
            send(FARMS, malformedKey, event(UUID.randomUUID(), "grower.farm.updated", "{\"farmId\":\"abc\",\"growerId\":\"1\"}"));
            send(PRODUCTS, catalogKey, "not-json");
            var down = awaitDlqRecord(farmDlq, downKey);
            assertThat(header(down, "kafka_dlt-exception-cause-fqcn")).endsWith("DependencyUnavailableException");
            assertThat(owners.farmCalls(G1, farmId)).isEqualTo(4);               // 1 try + 3 retries
            assertThat(header(awaitDlqRecord(farmDlq, malformedKey), "kafka_dlt-exception-cause-fqcn")).endsWith("MalformedEventException");
            assertThat(awaitDlqRecord(catalogDlq, catalogKey).value()).isEqualTo("not-json");
        }
    }

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
