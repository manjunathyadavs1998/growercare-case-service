package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.GrowerDirectory.GrowerReplica;
import com.growercare.casesvc.application.port.GrowerReplicaStore;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.common.sql.NamedSqlRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link GrowerReplicaStore} over {@code ref.grower} ({@code sql/postgres/case-grower-replica.sql}). */
@Repository
class JdbcGrowerReplicaStore implements GrowerReplicaStore {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcGrowerReplicaStore(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void upsert(GrowerReplica g) {
        try {
            jdbc.sql(sql.get("case-grower-replica.upsert"))
                    .param("growerId", g.growerId())
                    .param("countryCode", g.countryCode())
                    .param("territoryId", g.territoryId())
                    .param("userId", g.userId())
                    .param("displayName", g.displayName())
                    .param("phoneHmac", g.phoneHmac())
                    .param("phoneLast4", g.phoneLast4())
                    .param("preferredPartnerOrgId", g.preferredPartnerOrgId())
                    .param("status", g.status())
                    .param("version", g.version())
                    .update();
        } catch (DataIntegrityViolationException e) {
            // D7: territory or partner not (yet) in case-service's reference data — transient until the master arrives
            throw new DependencyUnavailableException("reference data for grower " + g.growerId(), e);
        }
    }
}
