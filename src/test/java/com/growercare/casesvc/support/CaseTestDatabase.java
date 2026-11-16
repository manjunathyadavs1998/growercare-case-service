package com.growercare.casesvc.support;

import com.growercare.shard.Country;
import com.growercare.shard.CountryRegistry;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import com.growercare.shard.migration.ShardedFlywayMigrator;
import com.growercare.testsupport.PostgresTestDatabase;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The case database for integration tests, kept <b>light</b>: the common schema + dev seed, but only the shard
 * schemas the tests actually use (≈10 instead of 128), migrated <b>once per JVM</b>.
 * <ul>
 *   <li>the shards every dev-seed grower routes to ({@link ShardRouter}, so fixtures land in existing schemas);</li>
 *   <li>the fixed shards used by {@code CaseSchemaMigrationIT} ({@link #FIXED_SHARDS}).</li>
 * </ul>
 * The full 128-shard migration is a production/startup concern, covered by running the service locally.
 */
public final class CaseTestDatabase {

    /** Shard DDL is serialised by an advisory lock anyway; low parallelism keeps laptop Docker VMs healthy. */
    public static final int MIGRATION_PARALLELISM = 2;

    /** Same registry as ref.country seed (IN index 46 / CC 71, BR index 8 / CC 31). */
    public static final ShardRouter ROUTER = new ShardRouter(new CountryRegistry(List.of(
            new Country("IN", 46, 71, "APAC", ZoneId.of("Asia/Kolkata")),
            new Country("BR", 8, 31, "LATAM", ZoneId.of("America/Sao_Paulo")))));

    /** Dev-seed growers (db/case/dev-seed) by country. */
    public static final Map<Long, String> SEED_GROWERS = Map.of(70001L, "IN", 70002L, "IN", 70004L, "IN", 70003L, "BR");

    /** Shards referenced by literal schema name in schema tests. */
    public static final Set<ShardId> FIXED_SHARDS = Set.of(
            new ShardId("IN", 1), new ShardId("IN", 2), new ShardId("IN", 3), new ShardId("IN", 4),
            new ShardId("IN", 5), new ShardId("IN", 27), new ShardId("BR", 5));

    /** Every shard the test database contains. */
    public static final Set<ShardId> TEST_SHARDS = Stream.concat(
                    FIXED_SHARDS.stream(),
                    SEED_GROWERS.entrySet().stream().map(e -> ROUTER.forGrower(e.getValue(), e.getKey())))
            .collect(Collectors.toUnmodifiableSet());

    private static boolean migrated;

    private CaseTestDatabase() { }

    public static synchronized PostgresTestDatabase migrated() {
        var db = PostgresTestDatabase.forService("gc_case");
        if (!migrated) {
            migrator(db).migrate();
            // Keep ref.shard consistent with the light database: list fan-out (SPEC-CASE-02 D1) reads the shard map,
            // so it must only name schemas that exist. Owner role; test-only.
            var keep = TEST_SHARDS.stream().map(ShardId::schemaName).toList();
            org.springframework.jdbc.core.simple.JdbcClient.create(db.ownerDataSource())
                    .sql("DELETE FROM ref.shard WHERE schema_name NOT IN (:keep)").param("keep", keep).update();
            migrated = true;
        }
        return db;
    }

    public static ShardedFlywayMigrator migrator(PostgresTestDatabase db) {
        return new ShardedFlywayMigrator(db.ownerDataSource(), new ShardedFlywayMigrator.Settings(
                List.of("classpath:db/case/common", "classpath:db/case/dev-seed"),
                List.of("classpath:db/case/shard"),
                Map.of("app_role", db.appUser(), "cdc_role", PostgresTestDatabase.CDC_ROLE),
                MIGRATION_PARALLELISM,
                TEST_SHARDS::contains));
    }
}
