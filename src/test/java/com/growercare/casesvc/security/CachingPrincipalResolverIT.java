package com.growercare.casesvc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.casesvc.support.DevSeedPrincipals;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.cache.CachingPrincipalResolver;
import com.growercare.testsupport.RedisTestContainer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** ADR-011: Redis caches principals, degrades gracefully, and never fails open. */
class CachingPrincipalResolverIT {

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    final AtomicInteger iamCalls = new AtomicInteger();
    final Map<UUID, GcPrincipal> iam = Map.of(DevSeedPrincipals.CARE_IN.subject(), DevSeedPrincipals.CARE_IN);
    final PrincipalResolver fakeIam = subject -> {
        iamCalls.incrementAndGet();
        return Optional.ofNullable(iam.get(subject));
    };
    CachingPrincipalResolver resolver;

    @BeforeAll
    static void redis() {
        var config = new RedisStandaloneConfiguration(RedisTestContainer.host(), RedisTestContainer.port());
        config.setPassword(RedisPassword.of(RedisTestContainer.PASSWORD));
        connections = new LettuceConnectionFactory(config,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void close() {
        connections.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("gc:principal:*"));
        resolver = new CachingPrincipalResolver(fakeIam, redis, JsonMapper.builder().build(), Duration.ofMinutes(5));
    }

    @Test
    void secondLookupIsServedFromRedisWithTtl() {
        var subject = DevSeedPrincipals.CARE_IN.subject();
        assertThat(resolver.resolve(subject)).contains(DevSeedPrincipals.CARE_IN);
        assertThat(resolver.resolve(subject)).contains(DevSeedPrincipals.CARE_IN);
        assertThat(iamCalls).hasValue(1);
        assertThat(redis.getExpire("gc:principal:" + subject)).isBetween(1L, 300L);
    }

    @Test
    void unknownAccountsAreNotCached() {
        var stranger = UUID.randomUUID();
        assertThat(resolver.resolve(stranger)).isEmpty();
        assertThat(resolver.resolve(stranger)).isEmpty();
        assertThat(iamCalls).hasValue(2);
    }

    @Test
    void evictForcesAFreshLookup() {
        var subject = DevSeedPrincipals.CARE_IN.subject();
        resolver.resolve(subject);
        resolver.evict(subject);
        resolver.resolve(subject);
        assertThat(iamCalls).hasValue(2);
    }

    @Test
    void redisOutageFallsBackToIam() {
        RedisTestContainer.pause();
        try {
            assertThat(resolver.resolve(DevSeedPrincipals.CARE_IN.subject())).contains(DevSeedPrincipals.CARE_IN);
            assertThat(iamCalls).hasValue(1);
        } finally {
            RedisTestContainer.unpause();
        }
    }

    @Test
    void iamOutageIsNeverMaskedAsAnonymous() {
        var failing = new CachingPrincipalResolver(subject -> {
            throw new DependencyUnavailableException("identity-access-service", null);
        }, redis, JsonMapper.builder().build(), Duration.ofMinutes(5));
        assertThatThrownBy(() -> failing.resolve(UUID.randomUUID())).isInstanceOf(DependencyUnavailableException.class);
    }
}
