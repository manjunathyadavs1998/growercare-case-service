package com.growercare.casesvc.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * catalog-registry-service's internal views (SPEC-CAT-01 R20/R21, SPEC-CASE-08 D1/D2). Empty when the catalog does not
 * know the product or batch; {@code DependencyUnavailableException} when it cannot answer.
 */
public interface CatalogDirectory {

    record RegistrationReplica(String countryCode, String registrationNo, String status, LocalDate validUntil, long version) { }

    record ProductReplica(long productId, String productCode, String buCode, String name, String kind, long version,
                          List<RegistrationReplica> registrations) {
        public ProductReplica {
            registrations = List.copyOf(registrations);
        }
    }

    record BatchReplica(long productId, String batchNo, LocalDate mfgDate, LocalDate expiryDate, String plantCode, long version) { }

    Optional<ProductReplica> fetchProduct(long productId);

    Optional<BatchReplica> fetchBatch(long productId, String batchNo);
}
