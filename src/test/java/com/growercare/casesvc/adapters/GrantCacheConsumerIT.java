package com.growercare.casesvc.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.adapters.in.messaging.GrantEventsListener;
import com.growercare.casesvc.adapters.out.cache.RedisPrincipalCacheEvictor;
import com.growercare.casesvc.application.port.PrincipalCacheEvictor;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.security.cache.CachingPrincipalResolver;
import com.growercare.testsupport.KafkaTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SPEC-PLAT-04 R56–R60 / E65–E69, E75: case-service consumes {@code iam.grant.events} idempotently (inbox) and
 * evicts cached principals; poison records go to the DLQ. Real Kafka, Postgres and Redis.
 * Every row uses its own subject, event id and key, so rows are independent.
 */
@Import(GrantCacheConsumerIT.Doubles.class)
class GrantCacheConsumerIT extends CaseApiTestBase {

    static final String TOPIC = "iam.grant.events";
    static final String DLQ = "dlq.case-service.iam-grant-cache";

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KafkaTestContainer::bootstrapServers);
        r.add("gc.events.consumers-enabled", () -> "true");
        r.add("gc.events.retry-initial-backoff", () -> "50ms");
    }

    /** Wraps the real Redis evictor: counts attempts per subject and can fail a number of times first. */
    static final class CountingEvictor implements PrincipalCacheEvictor {
        final PrincipalCacheEvictor real;
        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

        CountingEvictor(PrincipalCacheEvictor real) {
            this.real = real;
        }

        @Override
        public void evict(UUID subject) {
            attempts.computeIfAbsent(subject, s -> new AtomicInteger()).incrementAndGet();
            var left = failuresLeft.get(subject);
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("simulated Redis outage");
            }
            real.evict(subject);
        }

        int attempts(UUID subject) {
            var n = attempts.get(subject);
            return n == null ? 0 : n.get();
        }
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        @Primary
        CountingEvictor countingEvictor(RedisPrincipalCacheEvictor real) {
            return new CountingEvictor(real);
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CountingEvictor evictor;

    static String grantEvent(UUID eventId, String type, UUID subject) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s",
                 "data":{"grantId":"9001","userId":"101","subject":"%s","roleCode":"CARE_AGENT","actorUserId":"104"}}"""
                .formatted(eventId, type, Instant.now(), subject);
    }

    void send(String key, String value) throws Exception {
        kafka.send(TOPIC, key, value).get();
    }

    int inboxRows(UUID eventId) {
        return owner.sql("SELECT count(*) FROM msg.inbox WHERE consumer_group = ? AND event_id = ?")
                .params(GrantEventsListener.GROUP, eventId).query(Integer.class).single();
    }

    void awaitInbox(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> inboxRows(eventId) == 1);
    }

    // ------------------------------------------------------------------------------------------ R58 / E67

    @ParameterizedTest(name = "PLAT-E67 {0} evicts the cached principal")
    @ValueSource(strings = {"iam.grant.created", "iam.grant.revoked"})
    void grantChangesEvictTheCachedPrincipal(String eventType) throws Exception {
        var subject = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var key = CachingPrincipalResolver.cacheKey(subject);
        redis.opsForValue().set(key, "{\"cached\":true}");

        send("9001", grantEvent(eventId, eventType, subject));

        awaitInbox(eventId);
        assertThat(redis.hasKey(key)).isFalse();
    }

    // ------------------------------------------------------------------------------------------ R56 / E65

    @Test
    void platE65DuplicateDeliveryRunsTheSideEffectOnce() throws Exception {
        var subject = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var event = grantEvent(eventId, "iam.grant.revoked", subject);
        var marker = UUID.randomUUID();

        send("e65", event);
        send("e65", event);                                                     // redelivery of the same event
        send("e65", grantEvent(marker, "iam.grant.created", UUID.randomUUID())); // same partition, consumed after both

        awaitInbox(marker);
        assertThat(evictor.attempts(subject)).isEqualTo(1);
        assertThat(inboxRows(eventId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ R57 / E66

    @Test
    void platE66FailedSideEffectRollsBackTheInboxRowAndIsRetried() throws Exception {
        var subject = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        evictor.failuresLeft.put(subject, new AtomicInteger(1));

        send("e66", grantEvent(eventId, "iam.grant.revoked", subject));

        awaitInbox(eventId);
        assertThat(evictor.attempts(subject)).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------ R59 / E68

    @Test
    void platE68UnknownEventTypesAreIgnoredButRecorded() throws Exception {
        var subject = UUID.randomUUID();
        var eventId = UUID.randomUUID();

        send("e68", grantEvent(eventId, "iam.grant.renamed", subject));

        awaitInbox(eventId);
        assertThat(evictor.attempts(subject)).isZero();
    }

    // ------------------------------------------------------------------------------------------ R60 / E69, E75

    /** poison value, failures to inject for the subject of the poison (E75), expected exception class fragment. */
    static Stream<Arguments> poison() {
        var outage = UUID.randomUUID();
        return Stream.of(
                argumentSet("PLAT-E69a not JSON", "not-json", null, "MalformedEventException"),
                argumentSet("PLAT-E69b envelope without eventId",
                        "{\"eventType\":\"iam.grant.revoked\",\"data\":{\"subject\":\"" + UUID.randomUUID() + "\"}}", null,
                        "MalformedEventException"),
                argumentSet("PLAT-E69c grant event without subject",
                        "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"iam.grant.revoked\",\"data\":{\"grantId\":\"1\"}}",
                        null, "MalformedEventException"),
                argumentSet("PLAT-E75 eviction keeps failing (Redis down)",
                        grantEvent(UUID.randomUUID(), "iam.grant.revoked", outage), outage, "IllegalStateException"));
    }

    @ParameterizedTest
    @MethodSource("poison")
    void poisonRecordsGoToTheDlqAndThePartitionMovesOn(String value, UUID failingSubject, String exceptionClass) throws Exception {
        if (failingSubject != null) {
            evictor.failuresLeft.put(failingSubject, new AtomicInteger(Integer.MAX_VALUE));
        }
        var key = "poison-" + UUID.randomUUID();
        var next = UUID.randomUUID();

        try (var dlq = dlqConsumer()) {
            send(key, value);
            send(key, grantEvent(next, "iam.grant.created", UUID.randomUUID()));     // same key ⇒ same partition, after it

            var dead = awaitDlqRecord(dlq, key);
            assertThat(dead.value()).isEqualTo(value);
            assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).endsWith(exceptionClass);   // fqcn = the listener wrapper
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
            awaitInbox(next);
            if (failingSubject != null) {
                assertThat(evictor.attempts(failingSubject)).isEqualTo(4);          // 1 try + 3 retries
            }
        }
    }

    private static KafkaConsumer<String, String> dlqConsumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(DLQ));
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
