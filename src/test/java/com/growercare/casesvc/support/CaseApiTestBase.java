package com.growercare.casesvc.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.application.port.AssigneeDirectory;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import com.growercare.shard.ShardId;
import com.growercare.testsupport.RedisTestContainer;
import com.growercare.testsupport.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Spring context for case-service API tests (one cached context for all subclasses): light test database,
 * Redis container, test JWTs, and IAM replaced by the dev-seed principals ({@link DevSeedPrincipals}).
 */
@SpringBootTest(properties = "gc.migration.enabled=false")
@AutoConfigureMockMvc
@Import(CaseApiTestBase.Stubs.class)
public abstract class CaseApiTestBase {

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
    public static class Stubs {
        static final Map<UUID, GcPrincipal> BY_SUBJECT = DevSeedPrincipals.all().stream()
                .collect(Collectors.toMap(GcPrincipal::subject, Function.identity()));
        static final Map<Long, GcPrincipal> BY_USER_ID = DevSeedPrincipals.all().stream()
                .collect(Collectors.toMap(GcPrincipal::userId, Function.identity()));

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        @Bean
        @Primary
        PrincipalResolver stubIamBySubject() {
            return subject -> Optional.ofNullable(BY_SUBJECT.get(subject));
        }

        @Bean
        @Primary
        AssigneeDirectory stubIamByUserId() {
            return userId -> Optional.ofNullable(BY_USER_ID.get(userId));
        }
    }

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected MockMvc mvc;

    protected final JdbcClient owner = JdbcClient.create(CaseTestDatabase.migrated().ownerDataSource());

    protected ResultActions as(GcPrincipal actor, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", TestJwts.user(actor.subject()).bearer()));
    }

    protected ResultActions create(GcPrincipal actor, Map<String, Object> body, UUID idempotencyKey) throws Exception {
        var request = post("/cases").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey.toString());
        }
        return as(actor, request);
    }

    /** Creates a case and returns its id (string, as in the API). */
    protected String createCase(GcPrincipal actor, Map<String, Object> body) throws Exception {
        var json = create(actor, body, UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.caseId");
    }

    protected ResultActions getCase(GcPrincipal actor, String caseId) throws Exception {
        return as(actor, get("/cases/{id}", caseId));
    }

    protected static ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** A complaint by grower 70001 (Pune, CP), the spec's typical case "A". */
    protected static Map<String, Object> complaint() {
        var body = new LinkedHashMap<String, Object>();
        body.put("growerId", "70001");
        body.put("caseType", "PRODUCT_COMPLAINT");
        body.put("title", "Leaves yellowing after spray");
        body.put("description", "Brown spots appeared three days after spraying.");
        body.put("farmId", "80001");
        body.put("productId", "9001");
        body.put("batchNo", "AZX2604-117");
        return body;
    }

    protected static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
        var copy = new LinkedHashMap<>(body);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                copy.remove((String) keyValues[i]);
            } else {
                copy.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return copy;
    }

    protected static ShardId shardOf(String caseId) {
        return CaseTestDatabase.ROUTER.forId(Long.parseLong(caseId)).orElseThrow();
    }

    /** Test-only shortcut to put a case into a workflow state (owner role). */
    protected void forceStatus(String caseId, String status, boolean closed) {
        owner.sql("UPDATE %s.cases SET status_code = ?, closed_at = %s WHERE case_id = ?"
                        .formatted(shardOf(caseId).schemaName(), closed ? "now()" : "NULL"))
                .params(status, Long.parseLong(caseId)).update();
    }

    protected int count(String sqlWithShard, String caseId) {
        return owner.sql(sqlWithShard.replace("{shard}", shardOf(caseId).schemaName()))
                .param(Long.parseLong(caseId)).query(Integer.class).single();
    }
}
