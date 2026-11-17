package com.growercare.casesvc.adapters.in.messaging;

import com.growercare.casesvc.application.ReferenceChangeHandler;
import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.InboxDeduplicator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound adapters for {@code grower.farm.events} and the catalog topics (SPEC-CASE-08 R160–R165). Same shape as
 * {@link GrowerEventsListener}: the inbox row, the owner fetch and the upsert share one transaction, so a duplicate
 * delivery fetches once; retries and each consumer's DLQ come from {@code CaseEventsConfig}.
 */
@Component
public class ReferenceEventsListener {

    public static final String FARM_GROUP = "case-service.farm-replica";
    public static final String CATALOG_GROUP = "case-service.catalog-replica";

    private final ReferenceChangeHandler handler;
    private final InboxDeduplicator inbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ReferenceEventsListener(ReferenceChangeHandler handler, InboxDeduplicator inbox, TransactionTemplate tx, JsonMapper json) {
        this.handler = handler;
        this.inbox = inbox;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(id = "farm-replica", groupId = FARM_GROUP, topics = "${gc.events.farm-topic:grower.farm.events}",
            autoStartup = "${gc.events.consumers-enabled:true}")
    public void onFarmEvent(ConsumerRecord<String, String> record) {
        var event = EventEnvelope.parse(json, record.value());          // malformed → DLQ without retry
        tx.executeWithoutResult(status -> {
            if (inbox.firstDelivery(FARM_GROUP, event.eventId())) {
                handler.handleFarm(event);
            }
        });
    }

    @KafkaListener(id = "catalog-replica", groupId = CATALOG_GROUP,
            topics = {"${gc.events.product-topic:catalog.product.events}", "${gc.events.batch-topic:catalog.batch.events}"},
            autoStartup = "${gc.events.consumers-enabled:true}")
    public void onCatalogEvent(ConsumerRecord<String, String> record) {
        var event = EventEnvelope.parse(json, record.value());
        tx.executeWithoutResult(status -> {
            if (inbox.firstDelivery(CATALOG_GROUP, event.eventId())) {
                handler.handleCatalog(event);
            }
        });
    }
}
