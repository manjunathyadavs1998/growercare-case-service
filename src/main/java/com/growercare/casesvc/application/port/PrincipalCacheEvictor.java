package com.growercare.casesvc.application.port;

import java.util.UUID;

/** SPEC-PLAT-04 R58: drops a subject's cached principal. Failures are thrown, so the consumer retries (R57). */
@FunctionalInterface
public interface PrincipalCacheEvictor {

    void evict(UUID subject);
}
