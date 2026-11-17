package com.growercare.casesvc.application.port;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * grower-service's internal farm view (SPEC-GRW-02 R213, SPEC-CASE-08 D1). Empty when grower-service does not know the
 * farm for that grower; {@code DependencyUnavailableException} when it cannot answer.
 */
public interface FarmDirectory {

    /** The exact point (case-service applies its own clearance rules when showing farms). */
    record FarmReplica(long farmId, long growerId, String name, BigDecimal areaHa, BigDecimal latitude, BigDecimal longitude,
                       String status, int version) {
        @Override
        public String toString() {
            return "FarmReplica[farmId=%d, growerId=%d, status=%s, version=%d]".formatted(farmId, growerId, status, version);
        }
    }

    Optional<FarmReplica> fetchFarm(long growerId, long farmId);
}
