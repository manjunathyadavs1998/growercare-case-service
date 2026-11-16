package com.growercare.casesvc.adapters.in.messaging;

import com.growercare.casesvc.application.GrantChangeHandler;
import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.InboxDeduplicator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound adapter for {@code iam.grant.events} (SPEC-PLAT-04 D6). The inbox row and the side effect share one
 * transaction (R56, R57); retries and the DLQ are configured in {@code CaseEventsConfig} (R60).
 * Offsets are committed by the container after the record is processed or dead-lettered.
 */
@Component
public class GrantEventsListener {

    public static final String GROUP = "case-service.iam-grant-cache";

    private final GrantChangeHandler handler;
    private final InboxDeduplicator inbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public GrantEventsListener(GrantChangeHandler handler, InboxDeduplicator inbox, TransactionTemplate tx, JsonMapper json) {
        this.handler = handler;
        this.inbox = inbox;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(id = "grant-cache", groupId = GROUP, topics = "${gc.events.grant-topic:iam.grant.events}",
            autoStartup = "${gc.events.consumers-enabled:true}")
    public void onGrantEvent(ConsumerRecord<String, String> record) {
        var event = EventEnvelope.parse(json, record.value());          // malformed → DLQ without retry
        tx.executeWithoutResult(status -> {
            if (inbox.firstDelivery(GROUP, event.eventId())) {
                handler.handle(event);
            }
        });
    }
}
