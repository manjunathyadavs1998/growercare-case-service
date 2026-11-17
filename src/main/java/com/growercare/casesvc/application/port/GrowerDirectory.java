package com.growercare.casesvc.application.port;

import java.util.Optional;

/**
 * grower-service's internal replica view (SPEC-CASE-06 D1/D2, SPEC-GRW-01 R42): the owner's current state of a grower.
 * Empty when grower-service does not know the id; {@code DependencyUnavailableException} when it cannot answer.
 */
public interface GrowerDirectory {

    /** The phone only as HMAC + last 4 (D8). {@code phoneHmac}, {@code userId} and the partner may be null. */
    record GrowerReplica(long growerId, String countryCode, long territoryId, Long userId, String displayName,
                         byte[] phoneHmac, String phoneLast4, Long preferredPartnerOrgId, String status, int version) {

        @Override
        public String toString() {
            return "GrowerReplica[growerId=%d, countryCode=%s, territoryId=%d, status=%s, version=%d]"
                    .formatted(growerId, countryCode, territoryId, status, version);
        }
    }

    Optional<GrowerReplica> fetch(long growerId);
}
