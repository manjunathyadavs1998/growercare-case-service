package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.shard.ShardId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReferenceData} over schema {@code ref} ({@code sql/postgres/case-ref.sql}). The shard map is cached. */
@Repository
class JdbcReferenceData implements ReferenceData {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;
    private final AtomicReference<Map<String, List<ShardId>>> shardMap = new AtomicReference<>();

    JdbcReferenceData(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    private static final RowMapper<Grower> GROWER = (rs, n) -> new Grower(
            rs.getLong("grower_id"), rs.getString("country_code"), rs.getLong("territory_id"), rs.getObject("user_id", Long.class));

    @Override
    public Optional<Grower> grower(long growerId) {
        return jdbc.sql(sql.get("case-ref.grower")).param("growerId", growerId).query(GROWER).optional();
    }

    @Override
    public List<Grower> growersOfUser(long userId) {
        return jdbc.sql(sql.get("case-ref.growersOfUser")).param("userId", userId).query(GROWER).list();
    }

    @Override
    public boolean farmOfGrower(long farmId, long growerId) {
        return count(jdbc.sql(sql.get("case-ref.farmOfGrower")).param("farmId", farmId).param("growerId", growerId).query(Long.class).single());
    }

    @Override
    public Optional<String> productBu(long productId) {
        return jdbc.sql(sql.get("case-ref.productBu")).param("productId", productId).query(String.class).optional();
    }

    @Override
    public boolean productRegistered(String countryCode, long productId) {
        return count(jdbc.sql(sql.get("case-ref.productRegistered")).param("countryCode", countryCode)
                .param("productId", productId).query(Long.class).single());
    }

    @Override
    public boolean batchOfProduct(long productId, String batchNo) {
        return count(jdbc.sql(sql.get("case-ref.batchOfProduct")).param("productId", productId)
                .param("batchNo", batchNo).query(Long.class).single());
    }

    @Override
    public Optional<CaseType> caseType(String code) {
        return jdbc.sql(sql.get("case-ref.caseType")).param("caseType", code)
                .query((rs, n) -> new CaseType(rs.getString("case_type_code"), rs.getInt("default_sla_hours"),
                        rs.getBoolean("active"), rs.getString("initial_status")))
                .optional();
    }

    @Override
    public Optional<Transition> transition(String caseType, String fromStatus, String toStatus) {
        return jdbc.sql(sql.get("case-ref.transitionPermission"))
                .param("caseType", caseType).param("fromStatus", fromStatus).param("toStatus", toStatus)
                .query((rs, n) -> new Transition(rs.getString("required_permission"), rs.getBoolean("to_closed")))
                .optional();
    }

    @Override
    public Optional<Partner> partner(long partnerOrgId) {
        return jdbc.sql(sql.get("case-ref.partner")).param("partnerOrgId", partnerOrgId)
                .query((rs, n) -> new Partner(rs.getLong("partner_org_id"), rs.getString("country_code"), rs.getString("status")))
                .optional();
    }

    @Override
    public Set<String> countriesOfTerritories(Set<Long> territoryIds) {
        if (territoryIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(sql.get("case-ref.territoryCountries")).param("territoryIds", List.copyOf(territoryIds))
                .query(String.class).list());
    }

    @Override
    public List<ShardId> shardsOf(String countryCode) {
        var map = shardMap.get();
        if (map == null) {
            map = jdbc.sql(sql.get("case-ref.shards"))
                    .query((rs, n) -> new ShardId(rs.getString("country_code"), rs.getInt("shard_no")))
                    .list().stream()
                    .collect(Collectors.groupingBy(ShardId::countryCode, Collectors.collectingAndThen(Collectors.toList(),
                            l -> l.stream().sorted(Comparator.comparingInt(ShardId::shardNo)).toList())));
            shardMap.set(map);
        }
        return map.getOrDefault(countryCode, List.of());
    }

    private static boolean count(long n) {
        return n > 0;
    }
}
