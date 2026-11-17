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

    /** Growers that get cases in tests, by country: the dev seed (grower-service ids, SPEC-CASE-06 D6) and test-only K1. */
    public static final Map<Long, String> SEED_GROWERS = Map.of(
            SeedGrowers.G1, "IN", SeedGrowers.G2, "IN", SeedGrowers.G3, "IN", SeedGrowers.G4, "BR", SeedGrowers.K1, "IN");

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
            var owner = org.springframework.jdbc.core.simple.JdbcClient.create(db.ownerDataSource());
            owner.sql("DELETE FROM ref.shard WHERE schema_name NOT IN (:keep)").param("keep", keep).update();
            // K1: test-only ACTIVE grower in Karnataka (the dev seed's G3 there is PENDING_CONSENT, SPEC-CASE-06 D4)
            owner.sql("""
                    INSERT INTO ref.grower (grower_id, country_code, territory_id, user_id, display_name, status, version)
                    VALUES (:id, 'IN', 1005, NULL, 'Kavya Gowda (test)', 'ACTIVE', 0)""").param("id", SeedGrowers.K1).update();
            owner.sql("INSERT INTO ref.farm (farm_id, grower_id, name, area_ha) VALUES (:farm, :grower, 'Kavya ragi plot', 0.60)")
                    .param("farm", SeedGrowers.FARM_K1).param("grower", SeedGrowers.K1).update();
            migrated = true;
        }
        return db;
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_GROWER = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A fresh grower id (not in any seed), encoded like grower-service's ids (it decodes to {@code country}, so the
     * read-through precondition of SPEC-CASE-06 D9 accepts it), whose cases route to a shard of the light test database.
     */
    public static long nextGrowerIdInTestShards(String country) {
        int countryIx = ROUTER.countries().byCode(country).orElseThrow().countryIx();
        while (true) {
            int n = NEXT_GROWER.getAndIncrement();
            long id = com.growercare.shard.IdCodec.encode(java.time.Instant.parse("2026-11-02T00:00:00Z").plusMillis(n / 1024),
                    countryIx, n % 64, n % 1024);
            if (TEST_SHARDS.contains(ROUTER.forGrower(country, id))) {
                return id;
            }
        }
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
