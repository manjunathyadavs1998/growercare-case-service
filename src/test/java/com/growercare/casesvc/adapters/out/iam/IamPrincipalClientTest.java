package com.growercare.casesvc.adapters.out.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.growercare.casesvc.support.DevSeedPrincipals;
import com.growercare.common.error.DependencyUnavailableException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class IamPrincipalClientTest {

    static final ClientRegistration IAM = ClientRegistration.withRegistrationId("iam")
            .clientId("case-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(IAM, "case-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));

    MockRestServiceServer server;
    IamPrincipalClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://iam.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IamPrincipalClient(builder.build(), TOKENS);
    }

    @Test
    void resolvesPrincipalWithServiceToken() {
        var expected = DevSeedPrincipals.CARE_IN;
        server.expect(requestTo("http://iam.test/internal/users/" + expected.subject() + "/principal"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(expected), MediaType.APPLICATION_JSON));
        assertThat(client.resolve(expected.subject())).contains(expected);
        server.verify();
    }

    @Test
    void unknownAccountIsEmptyAndFailuresAreDependencyUnavailable() {
        var subject = DevSeedPrincipals.CARE_IN.subject();
        server.expect(requestTo("http://iam.test/internal/users/" + subject + "/principal")).andRespond(withResourceNotFound());
        assertThat(client.resolve(subject)).isEmpty();

        server.reset();
        server.expect(requestTo("http://iam.test/internal/users/" + subject + "/principal")).andRespond(withServerError());
        assertThatThrownBy(() -> client.resolve(subject)).isInstanceOf(DependencyUnavailableException.class);
    }
}
