package com.growercare.casesvc.adapters.in.messaging;

import com.growercare.casesvc.application.GrowerChangeHandler;
import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.InboxDeduplicator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound adapter for {@code grower.grower.events} (SPEC-CASE-06). The inbox row and the replica upsert share one
 * transaction (PLAT-R56/R57); retries and this consumer's own DLQ are configured in {@code CaseEventsConfig}.
 */
@Component
public class GrowerEventsListener {

    public static final String GROUP = "case-service.grower-replica";

    private final GrowerChangeHandler handler;
    private final InboxDeduplicator inbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public GrowerEventsListener(GrowerChangeHandler handler, InboxDeduplicator inbox, TransactionTemplate tx, JsonMapper json) {
        this.handler = handler;
        this.inbox = inbox;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(id = "grower-replica", groupId = GROUP, topics = "${gc.events.grower-topic:grower.grower.events}",
            autoStartup = "${gc.events.consumers-enabled:true}")
    public void onGrowerEvent(ConsumerRecord<String, String> record) {
        var event = EventEnvelope.parse(json, record.value());          // malformed → DLQ without retry
        tx.executeWithoutResult(status -> {
            if (inbox.firstDelivery(GROUP, event.eventId())) {
                handler.handle(event);
            }
        });
    }
}
