package com.growercare.casesvc.support;

import com.growercare.casesvc.application.port.CatalogDirectory;
import com.growercare.casesvc.application.port.FarmDirectory;
import com.growercare.common.error.DependencyUnavailableException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for grower-service's farm view and catalog-registry-service's views in tests (SPEC-CASE-08). Starts empty:
 * the dev seed rows are already replicated. Tests add entities, make keys fail like an outage, and count calls per key.
 */
public final class StubReferenceOwners {

    private static final AtomicLong NEXT_FARM = new AtomicLong(9_200_000);
    private static final AtomicLong NEXT_PRODUCT = new AtomicLong(9_300_000);
    private static final AtomicInteger NEXT_BATCH = new AtomicInteger(1);

    /** Fresh ids that no seed or other test uses. */
    public static long nextFarmId() {
        return NEXT_FARM.getAndIncrement();
    }

    public static long nextProductId() {
        return NEXT_PRODUCT.getAndIncrement();
    }

    public static String nextBatchNo() {
        return "TB-%05d".formatted(NEXT_BATCH.getAndIncrement());
    }

    private final Map<String, Object> entities = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    public final FarmDirectory farms = (growerId, farmId) -> lookup("farm:" + growerId + "/" + farmId, FarmDirectory.FarmReplica.class);

    public final CatalogDirectory catalog = new CatalogDirectory() {
        @Override
        public Optional<ProductReplica> fetchProduct(long productId) {
            return lookup("product:" + productId, ProductReplica.class);
        }

        @Override
        public Optional<BatchReplica> fetchBatch(long productId, String batchNo) {
            return lookup("batch:" + productId + "/" + batchNo, BatchReplica.class);
        }
    };

    public void put(FarmDirectory.FarmReplica farm) {
        entities.put("farm:" + farm.growerId() + "/" + farm.farmId(), farm);
    }

    public void put(CatalogDirectory.ProductReplica product) {
        entities.put("product:" + product.productId(), product);
    }

    public void put(CatalogDirectory.BatchReplica batch) {
        entities.put("batch:" + batch.productId() + "/" + batch.batchNo(), batch);
    }

    public void failFarm(long growerId, long farmId) {
        failing.add("farm:" + growerId + "/" + farmId);
    }

    public void failProduct(long productId) {
        failing.add("product:" + productId);
    }

    public int farmCalls(long growerId, long farmId) {
        return count("farm:" + growerId + "/" + farmId);
    }

    public int productCalls(long productId) {
        return count("product:" + productId);
    }

    public int batchCalls(long productId, String batchNo) {
        return count("batch:" + productId + "/" + batchNo);
    }

    private int count(String key) {
        var n = calls.get(key);
        return n == null ? 0 : n.get();
    }

    private <T> Optional<T> lookup(String key, Class<T> type) {
        calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (failing.contains(key)) {
            throw new DependencyUnavailableException("stubbed owner", null);
        }
        return Optional.ofNullable(type.cast(entities.get(key)));
    }
}
