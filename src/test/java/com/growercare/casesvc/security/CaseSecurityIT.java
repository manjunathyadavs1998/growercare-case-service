package com.growercare.casesvc.security;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.casesvc.support.DevSeedPrincipals;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import com.growercare.testsupport.RedisTestContainer;
import com.growercare.testsupport.TestJwts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * case-service security wiring: JWT validation, principal resolution (IAM stubbed), fail-closed on IAM outage,
 * service tokens kept off user APIs, CORS and security headers. Business endpoints arrive in increment 3.
 */
@SpringBootTest(properties = {
        "gc.migration.enabled=false",                       // CaseTestDatabase migrated it already
        "gc.jobs.enabled=false",
        "gc.security.cors-allowed-origins=http://localhost:5173"})
@AutoConfigureMockMvc
@Import(CaseSecurityIT.Stubs.class)   // explicit: nested @TestConfiguration is not detected for @Nested classes (Spring 7)
class CaseSecurityIT {

    static final UUID IAM_DOWN = UUID.fromString("00000000-0000-4000-8000-0000000000d0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        var db = CaseTestDatabase.migrated();
        r.add("spring.datasource.url", db::jdbcUrl);
        r.add("spring.datasource.username", db::appUser);
        r.add("spring.datasource.password", db::appPassword);
        r.add("spring.data.redis.host", RedisTestContainer::host);
        r.add("spring.data.redis.port", RedisTestContainer::port);
        r.add("spring.data.redis.password", () -> RedisTestContainer.PASSWORD);
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        /** Stands in for IAM: dev-seed principals, plus one subject whose lookup fails like an IAM outage. */
        @Bean
        @Primary
        PrincipalResolver stubIam() {
            Map<UUID, GcPrincipal> known = DevSeedPrincipals.all().stream()
                    .collect(Collectors.toMap(GcPrincipal::subject, Function.identity()));
            return subject -> {
                if (IAM_DOWN.equals(subject)) {
                    throw new DependencyUnavailableException("identity-access-service", null);
                }
                return Optional.ofNullable(known.get(subject));
            };
        }
    }

    @Autowired
    MockMvc mvc;

    ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** SPEC-CASE-01 R7–R10: Authorization header (null = none), expected status and problem code. */
    static Stream<Arguments> rejectedCallers() {
        return Stream.of(
                argumentSet("CASE-R7 no token", (Supplier<String>) () -> null, 401, "UNAUTHENTICATED"),
                argumentSet("CASE-R8 valid token, no account", (Supplier<String>) () -> TestJwts.user(UUID.randomUUID()).bearer(), 401, "UNAUTHENTICATED"),
                argumentSet("CASE-R9 IAM unreachable fails closed", (Supplier<String>) () -> TestJwts.user(IAM_DOWN).bearer(), 503, "DEPENDENCY_UNAVAILABLE"),
                argumentSet("CASE-R10 service token not allow-listed", (Supplier<String>) () -> TestJwts.service("integration-service").bearer(), 401, "UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @MethodSource("rejectedCallers")
    void rejectedCallers(Supplier<String> authorization, int status, String code) throws Exception {
        var request = get("/cases");
        var header = authorization.get();
        if (header != null) {
            request.header("Authorization", header);
        }
        problem(mvc.perform(request), status, code);
    }

    /** Wiring check: an authenticated user passes security and reaches the API. */
    @Test
    void knownUserPassesSecurityAndReachesTheApi() throws Exception {
        mvc.perform(get("/cases").header("Authorization", TestJwts.user(TestJwts.CARE_IN).bearer())).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "PLAT-R2 {0} is not exposed by default")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html"})
    void apiDocsAreNotExposedByDefault(String path) throws Exception {
        problem(mvc.perform(get(path)), 401, "UNAUTHENTICATED");
    }

    @Test
    void healthIsPublicAndReportsDatabaseAndRedis() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void corsOnlyForConfiguredOriginsAndApiSecurityHeaders() throws Exception {
        mvc.perform(options("/cases").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/cases").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cases"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
