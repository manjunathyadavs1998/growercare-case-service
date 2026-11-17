package com.growercare.casesvc.config;

import com.growercare.casesvc.adapters.out.iam.IamPrincipalClient;
import com.growercare.casesvc.application.port.AssigneeDirectory;
import com.growercare.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.cache.CachingPrincipalResolver;
import com.growercare.security.web.GcSecurityProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** case-service: principals come from IAM over HTTP, cached in Redis. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CaseSecurityConfig.IamClientProperties.class, CaseSecurityConfig.GrowerClientProperties.class,
        CaseSecurityConfig.CatalogClientProperties.class})
public class CaseSecurityConfig {

    /** {@code gc.iam.*}: where IAM lives and how long we wait for it. */
    @ConfigurationProperties("gc.iam")
    public record IamClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        public IamClientProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        }
    }

    /** {@code gc.grower.*}: where grower-service lives (SPEC-CASE-06) and how long we wait for it. */
    @ConfigurationProperties("gc.grower")
    public record GrowerClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        public GrowerClientProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        }
    }

    /** SPEC-CASE-06: grower-service's internal replica view, same client-credentials token as for IAM. */
    @Bean
    com.growercare.casesvc.application.port.GrowerDirectory growerDirectory(GrowerClientProperties grower, OAuth2AuthorizedClientManager tokens) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(grower.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(grower.readTimeout());
        var rest = RestClient.builder().baseUrl(grower.baseUrl()).requestFactory(requestFactory).build();
        return new com.growercare.casesvc.adapters.out.grower.GrowerDirectoryClient(rest, tokens);
    }

    /** SPEC-CASE-08: grower-service's internal farm view (same base URL and timeouts as the grower view). */
    @Bean
    com.growercare.casesvc.application.port.FarmDirectory farmDirectory(GrowerClientProperties grower, OAuth2AuthorizedClientManager tokens) {
        return new com.growercare.casesvc.adapters.out.grower.FarmDirectoryClient(restClient(grower.baseUrl(), grower.connectTimeout(),
                grower.readTimeout()), tokens);
    }

    /** {@code gc.catalog.*}: where catalog-registry-service lives (SPEC-CASE-08) and how long we wait for it. */
    @ConfigurationProperties("gc.catalog")
    public record CatalogClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        public CatalogClientProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        }
    }

    /** SPEC-CASE-08: catalog-registry-service's internal views, same client-credentials token. */
    @Bean
    com.growercare.casesvc.application.port.CatalogDirectory catalogDirectory(CatalogClientProperties catalog, OAuth2AuthorizedClientManager tokens) {
        return new com.growercare.casesvc.adapters.out.catalog.CatalogDirectoryClient(restClient(catalog.baseUrl(), catalog.connectTimeout(),
                catalog.readTimeout()), tokens);
    }

    private static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var requestFactory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** Client-credentials tokens outside any user request (no HttpServletRequest needed), cached until expiry. */
    @Bean
    OAuth2AuthorizedClientManager iamTokenManager(ClientRegistrationRepository registrations,
                                                  OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    IamPrincipalClient iamPrincipalClient(IamClientProperties iam, OAuth2AuthorizedClientManager tokens) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(iam.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(iam.readTimeout());
        var rest = RestClient.builder().baseUrl(iam.baseUrl()).requestFactory(requestFactory).build();
        return new IamPrincipalClient(rest, tokens);
    }

    /** Request authentication: IAM behind the Redis cache (ADR-011). */
    @Bean
    PrincipalResolver principalResolver(IamPrincipalClient iam, StringRedisTemplate redis, JsonMapper json, GcSecurityProperties security) {
        return new CachingPrincipalResolver(iam::resolve, redis, json, security.principalCacheTtl());
    }

    /** Assignee validation (SPEC-CASE-02 D2): always fresh from IAM, not cached. */
    @Bean
    AssigneeDirectory assigneeDirectory(IamPrincipalClient iam) {
        return iam::findByUserId;
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) {
        return new OutboxWriter(jdbc, json);
    }
}
