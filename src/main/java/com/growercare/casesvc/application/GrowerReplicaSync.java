package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.GrowerDirectory;
import com.growercare.casesvc.application.port.GrowerReplicaStore;
import com.growercare.casesvc.application.port.ReferenceData;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Keeps {@code ref.grower} in step with grower-service (SPEC-CASE-06 D1–D3, D7). */
@Service
public class GrowerReplicaSync {

    private final GrowerDirectory directory;
    private final GrowerReplicaStore store;
    private final ReferenceData ref;

    public GrowerReplicaSync(GrowerDirectory directory, GrowerReplicaStore store, ReferenceData ref) {
        this.directory = directory;
        this.store = store;
        this.ref = ref;
    }

    /** R130/R133: fetch the current state and upsert it; a grower grower-service does not know is skipped. */
    public void refresh(long growerId) {
        directory.fetch(growerId).ifPresent(store::upsert);
    }

    /** R140/R141: grower-service's current state; empty when it does not know the id. Nothing is stored yet (D9). */
    public Optional<GrowerDirectory.GrowerReplica> fetch(long growerId) {
        return directory.fetch(growerId);
    }

    /** Stores an authorised fetch and returns the replica row (R140; D7/D10 failures propagate). */
    public ReferenceData.Grower store(GrowerDirectory.GrowerReplica grower) {
        store.upsert(grower);
        return ref.grower(grower.growerId()).orElseThrow();
    }
}
