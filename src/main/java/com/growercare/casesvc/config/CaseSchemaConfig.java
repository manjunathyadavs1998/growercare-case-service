package com.growercare.casesvc.config;

import com.growercare.shard.CountryRegistry;
import com.growercare.shard.CountryRegistryLoader;
import com.growercare.shard.ShardRouter;
import com.growercare.shard.migration.ShardedFlywayMigrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Schema bootstrap and routing for the sharded case database.
 * <ol>
 *   <li>{@link ShardedFlywayMigrator} runs first (when enabled) with a short-lived owner-role pool.</li>
 *   <li>{@link CountryRegistry} is then loaded from {@code ref.country} and backs the {@link ShardRouter}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
public class CaseSchemaConfig {

    @Bean
    @ConditionalOnProperty(prefix = "gc.migration", name = "enabled", havingValue = "true")
    ShardedFlywayMigrator.Report caseSchemaMigration(DataSourceProperties dataSource, MigrationProperties props) {
        var commonLocations = new ArrayList<String>();
        commonLocations.add("classpath:db/case/common");
        if (props.devSeed()) {
            commonLocations.add("classpath:db/case/dev-seed");
        }
        try (var owner = ownerDataSource(dataSource, props)) {
            var settings = new ShardedFlywayMigrator.Settings(
                    commonLocations,
                    java.util.List.of("classpath:db/case/shard"),
                    Map.of("app_role", props.appRole(), "cdc_role", props.cdcRole()),
                    props.parallelism(),
                    ShardedFlywayMigrator.Settings.ALL_SHARDS,
                    props.devSeed());   // SPEC-PLAT-04 R63: out-of-order only with the dev seed (never in production)
            return new ShardedFlywayMigrator(owner, settings).migrate();
        }
    }

    @Bean
    CountryRegistry countryRegistry(DataSource dataSource, ObjectProvider<ShardedFlywayMigrator.Report> migration) {
        migration.getIfAvailable();   // ordering: migrate before reading reference data
        return CountryRegistryLoader.load(JdbcClient.create(dataSource));
    }

    @Bean
    ShardRouter shardRouter(CountryRegistry countryRegistry) {
        return new ShardRouter(countryRegistry);
    }

    private static HikariDataSource ownerDataSource(DataSourceProperties dataSource, MigrationProperties props) {
        var config = new HikariConfig();
        config.setPoolName("case-migration-owner");
        config.setJdbcUrl(dataSource.determineUrl());
        config.setUsername(props.ownerUser());
        config.setPassword(props.ownerPassword());
        // Each shard's Flyway run holds ~2 connections (schema history + migration) while it waits on the shard DDL
        // advisory lock. A pool of parallelism + 1 deadlocks: the lock holder waits for a pooled connection that the
        // lock waiters hold. 3 per worker leaves headroom; the timeout turns any shortfall into an error, not a hang.
        config.setMaximumPoolSize(props.parallelism() * 3 + 1);
        config.setConnectionTimeout(java.time.Duration.ofSeconds(60).toMillis());
        return new HikariDataSource(config);
    }
}
