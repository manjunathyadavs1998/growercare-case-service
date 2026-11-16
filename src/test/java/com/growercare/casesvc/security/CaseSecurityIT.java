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
        "gc.events.consumers-enabled=false",                // no broker in this test
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

    /** SPEC-PLAT-05: every error is problem+json, including those raised before Spring MVC. */
    @org.junit.jupiter.api.Nested
    class ErrorResponses {

        static String bearer() {
            return TestJwts.user(TestJwts.CARE_IN).bearer();
        }

        /** request, status, code, field (null = not asserted), Allow header (null = not asserted). */
        static Stream<Arguments> errors() {
            return Stream.of(
                    argumentSet("PLAT-E78 encoded traversal is rejected by the firewall",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    get(java.net.URI.create("/cases/1/%2e%2e/attachments")).header("Authorization", bearer()), 400, "VALIDATION_FAILED", "path", null),
                    argumentSet("PLAT-E79 semicolon in the path is rejected by the firewall",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    get("/cases/1;jsessionid=x").header("Authorization", bearer()), 400, "VALIDATION_FAILED", "path", null),
                    argumentSet("PLAT-E80 unsupported method is 405 with Allow",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/cases").header("Authorization", bearer()),
                            405, "METHOD_NOT_ALLOWED", null, "GET,POST"),
                    argumentSet("PLAT-E81 unsupported content type is 415",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cases").header("Authorization", bearer())
                                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("text/plain").content("hello"), 415, "UNSUPPORTED_MEDIA_TYPE", null, null),
                    argumentSet("PLAT-E82 unsatisfiable Accept is 406",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    get("/cases").header("Authorization", bearer()).accept("image/png"), 406, "NOT_ACCEPTABLE", null, null),
                    argumentSet("PLAT-E83 malformed JSON is 400 on body",
                            (Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) () ->
                                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cases").header("Authorization", bearer())
                                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("{\"growerId\":"), 400, "VALIDATION_FAILED", "body", null));
        }

        @ParameterizedTest
        @MethodSource("errors")
        void everyErrorIsProblemJson(Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> request,
                                     int status, String code, String field, String allowContains) throws Exception {
            var result = problem(mvc.perform(request.get()), status, code)
                    .andExpect(jsonPath("$.traceId").exists())
                    .andExpect(jsonPath("$.trace").doesNotExist());
            if (field != null) {
                result.andExpect(jsonPath("$.errors[0].field").value(field));
            }
            if (allowContains != null) {
                for (var method : allowContains.split(",")) {                                   // every listed method (spec E80)
                    result.andExpect(header().string("Allow", containsString(method)));
                }
            }
        }
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
