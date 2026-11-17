package com.growercare.casesvc.adapters;

import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.application.port.CatalogDirectory.BatchReplica;
import com.growercare.casesvc.application.port.CatalogDirectory.ProductReplica;
import com.growercare.casesvc.application.port.CatalogDirectory.RegistrationReplica;
import com.growercare.casesvc.application.port.FarmDirectory.FarmReplica;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.SeedGrowers;
import com.growercare.casesvc.support.StubReferenceOwners;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-08 R170–R175 (CASE-E170…E175): case creation reads farms, products and batches through from their owners. */
class CaseCreationCatalogIT extends CaseApiTestBase {

    static final long G1 = SeedGrowers.G1;

    static FarmReplica farm(long farmId, long growerId, String status, int version) {
        return new FarmReplica(farmId, growerId, "Replica farm " + farmId, new BigDecimal("1.50"), new BigDecimal("18.500000"),
                new BigDecimal("73.800000"), status, version);
    }

    static ProductReplica product(long productId, String registrationStatus) {
        return new ProductReplica(productId, "TP-" + productId, "CP", "Test product " + productId, "FUNGICIDE", 1,
                List.of(new RegistrationReplica("IN", "CIR-T-" + productId, registrationStatus, LocalDate.of(2030, 1, 1), 1)));
    }

    static BatchReplica batch(long productId, String batchNo) {
        return new BatchReplica(productId, batchNo, LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1), "IN-GOA", 1);
    }

    int refRows(String sql, Object... params) {
        return owner.sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void CASE_E170_aNewFarmOfTheGrowerIsReadThroughAndTheCaseIsCreated() throws Exception {
        long farmId = StubReferenceOwners.nextFarmId();
        owners.put(farm(farmId, G1, "ACTIVE", 0));
        create(GROWER_IN1, with(complaint(), "farmId", Long.toString(farmId)), UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.farmId").value(Long.toString(farmId)));
        assertThat(refRows("SELECT count(*) FROM ref.farm WHERE farm_id = ? AND grower_id = ? AND status = 'ACTIVE'", farmId, G1)).isEqualTo(1);
        assertThat(owners.farmCalls(G1, farmId)).isEqualTo(1);
        create(GROWER_IN1, with(complaint(), "farmId", Long.toString(farmId)), UUID.randomUUID()).andExpect(status().isCreated());
        assertThat(owners.farmCalls(G1, farmId)).as("replicated now: no second call").isEqualTo(1);
    }

    @Test
    void CASE_E172_aNewProductAndItsBatchAreReadThrough() throws Exception {
        long productId = StubReferenceOwners.nextProductId();
        var batchNo = StubReferenceOwners.nextBatchNo();
        owners.put(product(productId, "REGISTERED"));
        owners.put(batch(productId, batchNo));
        create(GROWER_IN1, with(complaint(), "productId", Long.toString(productId), "batchNo", batchNo), UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.buCode").value("CP"));
        assertThat(refRows("SELECT count(*) FROM ref.product WHERE product_id = ? AND version = 1", productId)).isEqualTo(1);
        assertThat(refRows("SELECT count(*) FROM ref.product_registration WHERE product_id = ? AND country_code = 'IN' AND status = 'REGISTERED'",
                productId)).isEqualTo(1);
        assertThat(refRows("SELECT count(*) FROM ref.product_batch WHERE product_id = ? AND batch_no = ?", productId, batchNo)).isEqualTo(1);
    }

    /** How a row prepares the owners' stubs and changes the complaint; returns the body to send. */
    interface Setup {
        java.util.Map<String, Object> apply(com.growercare.casesvc.support.StubReferenceOwners owners);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                argumentSet("CASE-E171 an archived farm takes no new cases", (Setup) o -> {
                    long farmId = StubReferenceOwners.nextFarmId();
                    o.put(farm(farmId, G1, "ARCHIVED", 1));
                    return with(complaint(), "farmId", Long.toString(farmId));
                }, "case.farm-not-active"),
                argumentSet("CASE-E170b a farm grower-service does not know for G1", (Setup) o ->
                        with(complaint(), "farmId", Long.toString(StubReferenceOwners.nextFarmId())), "case.farm-not-of-grower"),
                argumentSet("CASE-E172b a new product withdrawn in IN", (Setup) o -> {
                    long productId = StubReferenceOwners.nextProductId();
                    o.put(product(productId, "WITHDRAWN"));
                    return with(complaint(), "productId", Long.toString(productId), "batchNo", null);
                }, "case.product-not-registered"),
                argumentSet("CASE-E173 a batch the catalog does not know", (Setup) o -> with(complaint(), "batchNo", "NOPE-0001"),
                        "case.batch-unknown"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void afterTheReadThroughTheBusinessRulesApply(Setup setup, String rule) throws Exception {
        problem(create(GROWER_IN1, setup.apply(owners), UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value(rule));
    }

    static Stream<Arguments> outages() {
        return Stream.of(argumentSet("CASE-E174 catalog down for a new product", "product"),
                argumentSet("CASE-E174 grower-service down for a new farm", "farm"));
    }

    @ParameterizedTest
    @MethodSource("outages")
    void readThroughFailuresAre503AndWriteNothing(String what) throws Exception {
        var body = complaint();
        long id;
        if (what.equals("product")) {
            id = StubReferenceOwners.nextProductId();
            owners.failProduct(id);
            body = with(body, "productId", Long.toString(id), "batchNo", null);
        } else {
            id = StubReferenceOwners.nextFarmId();
            owners.failFarm(G1, id);
            body = with(body, "farmId", Long.toString(id));
        }
        problem(create(GROWER_IN1, body, UUID.randomUUID()), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(refRows("SELECT count(*) FROM ref.product WHERE product_id = ?", id)
                + refRows("SELECT count(*) FROM ref.farm WHERE farm_id = ?", id)).isZero();
    }

    @Test
    void CASE_E175_anOutOfScopeCallerNeverTriggersAReadThrough() throws Exception {
        long farmId = StubReferenceOwners.nextFarmId();
        long productId = StubReferenceOwners.nextProductId();
        owners.put(farm(farmId, G1, "ACTIVE", 0));
        owners.put(product(productId, "REGISTERED"));
        problem(create(GROWER_IN2, with(complaint(), "farmId", Long.toString(farmId)), UUID.randomUUID()), 404, "NOT_FOUND");
        problem(create(GROWER_IN2, with(complaint(), "productId", Long.toString(productId), "batchNo", null), UUID.randomUUID()), 404, "NOT_FOUND");
        assertThat(owners.farmCalls(G1, farmId)).isZero();
        assertThat(owners.productCalls(productId)).isZero();
    }
}
