package com.growercare.casesvc.application.port;

import java.util.Optional;

/** Writes and probes the farm and catalog replicas (SPEC-CASE-08 R160–R163, R170–R173, R180). Version-guarded (D3). */
public interface ReferenceReplicaStore {

    /** A replicated farm: whose it is and whether it takes new cases. */
    record FarmRow(long growerId, String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /**
     * @throws com.growercare.common.error.DependencyUnavailableException when a referenced master row (the farm's grower,
     *         the product's BU or a registration's country) is missing: a transient gap (as SPEC-CASE-06 D7)
     * @throws com.growercare.common.error.ConflictException on any other integrity conflict (permanent, D10)
     */
    void upsertFarm(FarmDirectory.FarmReplica farm);

    /** The product and each of its registrations, each guarded by its own version. Same exceptions as {@link #upsertFarm}. */
    void upsertProduct(CatalogDirectory.ProductReplica product);

    void upsertBatch(CatalogDirectory.BatchReplica batch);

    Optional<FarmRow> farm(long farmId);

    boolean productKnown(long productId);

    boolean batchKnown(long productId, String batchNo);
}
