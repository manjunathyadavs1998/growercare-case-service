package com.growercare.casesvc.adapters.out.grower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.growercare.common.error.DependencyUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** SPEC-CASE-06 D1/D2/R142: reading grower-service's internal replica view, failing closed. */
class GrowerDirectoryClientTest {

    static final ClientRegistration CLIENT = ClientRegistration.withRegistrationId("iam")
            .clientId("case-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(CLIENT, "case-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));
    static final long G1 = 110165911145454593L;

    MockRestServiceServer server;
    GrowerDirectoryClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://grower.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GrowerDirectoryClient(builder.build(), TOKENS);
    }

    @Test
    void readsTheReplicaViewWithTheServiceToken() {
        var hmac = new byte[32];
        hmac[0] = 7;
        server.expect(requestTo("http://grower.test/internal/growers/" + G1))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("""
                        {"growerId":"%d","countryCode":"IN","territoryId":"1002","userId":"1","displayName":"Ramesh Patil",
                         "phoneHmac":"%s","phoneLast4":"3210","preferredPartnerOrgId":"501","status":"ACTIVE","version":2}"""
                        .formatted(G1, Base64.getEncoder().encodeToString(hmac)), MediaType.APPLICATION_JSON));
        var grower = client.fetch(G1).orElseThrow();
        assertThat(grower.growerId()).isEqualTo(G1);
        assertThat(grower.territoryId()).isEqualTo(1002L);
        assertThat(grower.userId()).isEqualTo(1L);
        assertThat(grower.preferredPartnerOrgId()).isEqualTo(501L);
        assertThat(grower.phoneHmac()).isEqualTo(hmac);
        assertThat(grower.version()).isEqualTo(2);
        server.verify();
    }

    @Test
    void absentOptionalFieldsAreNull() {
        server.expect(requestTo("http://grower.test/internal/growers/" + G1)).andRespond(withSuccess("""
                {"growerId":"%d","countryCode":"IN","territoryId":"1005","displayName":"Lakshmi","phoneLast4":"5432","status":"PENDING_CONSENT","version":0}"""
                .formatted(G1), MediaType.APPLICATION_JSON));
        var grower = client.fetch(G1).orElseThrow();
        assertThat(grower.userId()).isNull();
        assertThat(grower.preferredPartnerOrgId()).isNull();
        assertThat(grower.phoneHmac()).isNull();
    }

    static ResponseCreator truncatedBody() {
        return request -> new MockClientHttpResponse(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        }, HttpStatus.OK) {
            {
                getHeaders().setContentType(MediaType.APPLICATION_JSON);
            }
        };
    }

    static final OAuth2AuthorizedClientManager REFUSING_TOKENS = request -> {
        throw new ClientAuthorizationException(new OAuth2Error("invalid_client"), "iam");
    };

    static Stream<Arguments> failures() {
        return Stream.of(
                argumentSet("CASE-E142 grower-service 5xx", withServerError(), false),
                argumentSet("CASE-E142 unreadable body", truncatedBody(), false),
                argumentSet("CASE-E142 token endpoint refuses", null, false),
                argumentSet("CASE-E141 404 = no such grower", withResourceNotFound(), true));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void failuresFailClosed(ResponseCreator response, boolean unknown) {
        var target = client;
        if (response == null) {
            var builder = RestClient.builder().baseUrl("http://grower.test");
            MockRestServiceServer.bindTo(builder).build();
            target = new GrowerDirectoryClient(builder.build(), REFUSING_TOKENS);
        } else {
            server.expect(requestTo("http://grower.test/internal/growers/" + G1)).andRespond(response);
        }
        var finalTarget = target;
        if (unknown) {
            assertThat(finalTarget.fetch(G1)).isEmpty();
        } else {
            assertThatThrownBy(() -> finalTarget.fetch(G1)).isInstanceOf(DependencyUnavailableException.class);
        }
    }
}
