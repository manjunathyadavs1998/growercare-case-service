package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.PrincipalCacheEvictor;
import com.growercare.outbox.inbox.EventEnvelope;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SPEC-PLAT-04 R58/R59: IAM grant changes make case-service forget the cached principal of the affected subject,
 * so the change takes effect within the event lag instead of the cache TTL. Unknown event types are ignored.
 */
@Service
public class GrantChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(GrantChangeHandler.class);
    static final Set<String> HANDLED = Set.of("iam.grant.created", "iam.grant.revoked");

    private final PrincipalCacheEvictor cache;

    public GrantChangeHandler(PrincipalCacheEvictor cache) {
        this.cache = cache;
    }

    /** @return true when the event caused an eviction */
    public boolean handle(EventEnvelope event) {
        if (!HANDLED.contains(event.eventType())) {
            log.debug("ignoring event type {} (R59)", event.eventType());
            return false;
        }
        cache.evict(event.requiredUuid("subject"));
        return true;
    }
}
