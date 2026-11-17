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

    /** R140/R141: the replica row after fetching it from grower-service; empty when grower-service does not know it. */
    public Optional<ReferenceData.Grower> readThrough(long growerId) {
        var fetched = directory.fetch(growerId);
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        store.upsert(fetched.get());
        return ref.grower(growerId);
    }
}
