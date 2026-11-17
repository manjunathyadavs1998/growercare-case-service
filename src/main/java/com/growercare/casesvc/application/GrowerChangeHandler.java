package com.growercare.casesvc.application;

import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.MalformedEventException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SPEC-CASE-06 R130–R136: a grower event makes case-service refresh its replica of that grower from grower-service.
 * Unknown event types are ignored (PLAT-R59); an event without a usable growerId is malformed (DLQ, no retries).
 */
@Service
public class GrowerChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(GrowerChangeHandler.class);
    static final Set<String> HANDLED = Set.of("grower.registered", "grower.updated");
    private static final String ID = "^[1-9][0-9]{0,18}$";

    private final GrowerReplicaSync replicas;

    public GrowerChangeHandler(GrowerReplicaSync replicas) {
        this.replicas = replicas;
    }

    /** @return true when the event refreshed the replica */
    public boolean handle(EventEnvelope event) {
        if (!HANDLED.contains(event.eventType())) {
            log.debug("ignoring event type {} (PLAT-R59)", event.eventType());
            return false;
        }
        replicas.refresh(growerId(event));
        return true;
    }

    private static long growerId(EventEnvelope event) {
        var node = event.data() == null ? null : event.data().get("growerId");
        var raw = node == null || node.isNull() ? null : node.asString();
        if (raw == null || !raw.matches(ID)) {
            throw new MalformedEventException("grower event " + event.eventId() + " without a valid growerId");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new MalformedEventException("grower event " + event.eventId() + " with an out-of-range growerId", e);
        }
    }
}
