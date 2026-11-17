package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.CatalogDirectory;
import com.growercare.casesvc.application.port.FarmDirectory;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.application.port.ReferenceReplicaStore;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code ref.farm} and the catalog replicas in step with their owners (SPEC-CASE-08 D1–D3): refreshes from
 * events, read-through when a case names something not replicated yet. Callers authorise first (D8).
 */
@Service
public class ReferenceReplicaSync {

    private final FarmDirectory farms;
    private final CatalogDirectory catalog;
    private final ReferenceReplicaStore store;
    private final ReferenceData ref;
    private final GrowerReplicaSync growers;

    public ReferenceReplicaSync(FarmDirectory farms, CatalogDirectory catalog, ReferenceReplicaStore store, ReferenceData ref,
                                GrowerReplicaSync growers) {
        this.farms = farms;
        this.catalog = catalog;
        this.store = store;
        this.ref = ref;
        this.growers = growers;
    }

    /**
     * R160/R170: fetch the farm and store it; a farm whose grower is not replicated yet refreshes the grower first.
     *
     * @return whether grower-service knows that farm for that grower
     */
    public boolean refreshFarm(long growerId, long farmId) {
        var farm = farms.fetchFarm(growerId, farmId);
        if (farm.isEmpty()) {
            return false;
        }
        if (ref.grower(growerId).isEmpty()) {
            growers.refresh(growerId);
        }
        store.upsertFarm(farm.get());
        return true;
    }

    /** R161/R172: fetch the product with every registration and store it; false when the catalog does not know it. */
    public boolean refreshProduct(long productId) {
        var product = catalog.fetchProduct(productId);
        product.ifPresent(store::upsertProduct);
        return product.isPresent();
    }

    /** R173: fetch one batch and store it; false when the catalog does not know it for that product. */
    public boolean readThroughBatch(long productId, String batchNo) {
        var batch = catalog.fetchBatch(productId, batchNo);
        batch.ifPresent(store::upsertBatch);
        return batch.isPresent();
    }

    /** R162 / D2: only batches already replicated are refreshed by events. */
    public boolean refreshKnownBatch(long productId, String batchNo) {
        return store.batchKnown(productId, batchNo) && readThroughBatch(productId, batchNo);
    }
}
