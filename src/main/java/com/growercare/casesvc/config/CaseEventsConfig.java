package com.growercare.casesvc.config;

import com.growercare.outbox.inbox.InboxDeduplicator;
import com.growercare.outbox.inbox.MalformedEventException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * SPEC-PLAT-04 D5/D7: inbox + consumer error handling. Transient failures are retried 3 times with exponential
 * backoff (1 s, 2 s, 4 s by default), then the record goes to {@code dlq.case-service.iam-grant-cache} with Spring's
 * standard {@code kafka_dlt-*} headers. Malformed records skip the retries (R60).
 */
@Configuration(proxyBeanMethods = false)
public class CaseEventsConfig {

    public static final String DLQ_TOPIC = "dlq.case-service.iam-grant-cache";
    static final int RETRIES = 3;

    @Bean
    InboxDeduplicator inboxDeduplicator(JdbcClient jdbc) {
        return new InboxDeduplicator(jdbc, "msg.inbox");
    }

    /** Picked up by Boot's default listener container factory. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template,
                                         @Value("${gc.events.retry-initial-backoff:1s}") Duration initialBackoff) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(DLQ_TOPIC, -1));   // -1: partition by the original key
        var backOff = new ExponentialBackOff(initialBackoff.toMillis(), 2.0);
        backOff.setMaxAttempts(RETRIES);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
