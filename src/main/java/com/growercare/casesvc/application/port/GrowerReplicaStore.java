package com.growercare.casesvc.application.port;

/** Writes {@code ref.grower} (SPEC-CASE-06 R130/R131, R150): the one reference table case-service maintains. */
public interface GrowerReplicaStore {

    /**
     * Inserts the grower, or replaces the stored row when it has a lower version (D3).
     *
     * @throws com.growercare.common.error.DependencyUnavailableException when the grower's territory or partner is not
     *         in case-service's reference data yet (D7: a transient gap until the master arrives)
     * @throws com.growercare.common.error.ConflictException on any other integrity conflict, e.g. the grower's user is
     *         already linked to another replica grower (D10: permanent, not retried)
     */
    void upsert(GrowerDirectory.GrowerReplica grower);
}
