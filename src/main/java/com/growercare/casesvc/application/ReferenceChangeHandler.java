package com.growercare.casesvc.application;

import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.MalformedEventException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SPEC-CASE-08 R160–R164: farm and catalog events make case-service refresh the named entity from its owner. Unknown
 * event types are ignored (PLAT-R59); an event without usable ids is malformed (DLQ, no retries).
 */
@Service
public class ReferenceChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(ReferenceChangeHandler.class);
    static final Set<String> FARM_EVENTS = Set.of("grower.farm.created", "grower.farm.updated", "grower.farm.archived");
    static final Set<String> PRODUCT_EVENTS = Set.of("catalog.product.upserted", "catalog.registration.changed");
    static final String BATCH_EVENT = "catalog.batch.upserted";
    private static final String ID = "^[1-9][0-9]{0,18}$";
    private static final String BATCH_NO = "^[A-Z0-9-]{4,32}$";

    private final ReferenceReplicaSync replicas;

    public ReferenceChangeHandler(ReferenceReplicaSync replicas) {
        this.replicas = replicas;
    }

    /** @return true when the event refreshed a replica row */
    public boolean handleFarm(EventEnvelope event) {
        if (!FARM_EVENTS.contains(event.eventType())) {
            log.debug("ignoring event type {} (PLAT-R59)", event.eventType());
            return false;
        }
        return replicas.refreshFarm(id(event, "growerId"), id(event, "farmId"));
    }

    /** @return true when the event refreshed a replica row */
    public boolean handleCatalog(EventEnvelope event) {
        if (PRODUCT_EVENTS.contains(event.eventType())) {
            return replicas.refreshProduct(id(event, "productId"));
        }
        if (BATCH_EVENT.equals(event.eventType())) {
            return replicas.refreshKnownBatch(id(event, "productId"), text(event, "batchNo", BATCH_NO));
        }
        log.debug("ignoring event type {} (PLAT-R59)", event.eventType());
        return false;
    }

    private static long id(EventEnvelope event, String field) {
        var raw = text(event, field, ID);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new MalformedEventException("event " + event.eventId() + " with an out-of-range " + field, e);
        }
    }

    private static String text(EventEnvelope event, String field, String pattern) {
        var node = event.data() == null ? null : event.data().get(field);
        var raw = node == null || node.isNull() ? null : node.asString();
        if (raw == null || !raw.matches(pattern)) {
            throw new MalformedEventException("event " + event.eventId() + " without a valid " + field);
        }
        return raw;
    }
}
