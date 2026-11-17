package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.CatalogDirectory.BatchReplica;
import com.growercare.casesvc.application.port.CatalogDirectory.ProductReplica;
import com.growercare.casesvc.application.port.FarmDirectory.FarmReplica;
import com.growercare.casesvc.application.port.ReferenceReplicaStore;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.sql.NamedSqlRegistry;
import java.sql.Date;
import java.sql.Types;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReferenceReplicaStore} over {@code ref.farm}, {@code ref.product*} ({@code sql/postgres/case-catalog-replica.sql}). */
@Repository
class JdbcReferenceReplicaStore implements ReferenceReplicaStore {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcReferenceReplicaStore(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    private JdbcClient.StatementSpec statement(String name) {
        return jdbc.sql(sql.get("case-catalog-replica." + name));
    }

    @Override
    public void upsertFarm(FarmReplica f) {
        write("farm " + f.farmId(), () -> statement("upsertFarm")
                .param("farmId", f.farmId()).param("growerId", f.growerId()).param("name", f.name()).param("areaHa", f.areaHa())
                .param("latitude", f.latitude(), Types.NUMERIC).param("longitude", f.longitude(), Types.NUMERIC)
                .param("status", f.status()).param("version", f.version())
                .update());
    }

    @Override
    public void upsertProduct(ProductReplica p) {
        write("product " + p.productId(), () -> {
            statement("upsertProduct").param("productId", p.productId()).param("productCode", p.productCode())
                    .param("buCode", p.buCode()).param("name", p.name()).param("kind", p.kind()).param("version", p.version())
                    .update();
            for (var r : p.registrations()) {
                statement("upsertRegistration").param("countryCode", r.countryCode()).param("productId", p.productId())
                        .param("registrationNo", r.registrationNo()).param("status", r.status())
                        .param("validUntil", r.validUntil() == null ? null : Date.valueOf(r.validUntil()), Types.DATE)
                        .param("version", r.version())
                        .update();
            }
            return 0;
        });
    }

    @Override
    public void upsertBatch(BatchReplica b) {
        write("batch " + b.productId() + "/" + b.batchNo(), () -> statement("upsertBatch")
                .param("productId", b.productId()).param("batchNo", b.batchNo())
                .param("mfgDate", Date.valueOf(b.mfgDate())).param("expiryDate", Date.valueOf(b.expiryDate()))
                .param("plantCode", b.plantCode()).param("version", b.version())
                .update());
    }

    @Override
    public Optional<FarmRow> farm(long farmId) {
        return statement("farm").param("farmId", farmId)
                .query((rs, n) -> new FarmRow(rs.getLong("grower_id"), rs.getString("status"))).optional();
    }

    @Override
    public boolean productKnown(long productId) {
        return Boolean.TRUE.equals(statement("productKnown").param("productId", productId).query(Boolean.class).single());
    }

    @Override
    public boolean batchKnown(long productId, String batchNo) {
        return Boolean.TRUE.equals(statement("batchKnown").param("productId", productId).param("batchNo", batchNo)
                .query(Boolean.class).single());
    }

    /** Same error mapping as the grower replica (SPEC-CASE-06 D7/D10): missing master row = transient, else permanent. */
    private static void write(String what, java.util.function.Supplier<Integer> statement) {
        try {
            statement.get();
        } catch (DataIntegrityViolationException e) {
            if (JdbcGrowerReplicaStore.isForeignKeyViolation(e)) {
                throw new DependencyUnavailableException("reference data for " + what, e);
            }
            throw new ConflictException(ErrorCode.DUPLICATE, what + " conflicts with the replica", e);
        }
    }
}
