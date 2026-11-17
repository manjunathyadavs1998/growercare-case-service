package com.growercare.casesvc.adapters.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.growercare.common.error.DependencyUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** SPEC-CASE-08 D7 (CASE-E172–E174): reading catalog-registry-service's internal views (SPEC-CAT-01 R20/R21). */
class CatalogDirectoryClientTest {

    static final ClientRegistration CASE = ClientRegistration.withRegistrationId("iam")
            .clientId("case-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(CASE, "case-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));

    MockRestServiceServer server;
    CatalogDirectoryClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://catalog.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogDirectoryClient(builder.build(), TOKENS);
    }

    @Test
    void CASE_E172_theProductViewIsReadWithEveryVersionedRegistration() {
        server.expect(requestTo("http://catalog.test/internal/products/9001"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("""
                        {"productId":"9001","productCode":"AZX-325SC","buCode":"CP","name":"Azoxa Duo 325 SC","kind":"FUNGICIDE",
                         "status":"ACTIVE","version":3,"activeIngredients":[],"packSizes":[],
                         "registrations":[{"productId":"9001","countryCode":"IN","registrationNo":"CIR-1","status":"REGISTERED",
                                           "validUntil":"2029-12-31","version":2},
                                          {"productId":"9001","countryCode":"BR","registrationNo":"MAPA-1","status":"SUSPENDED",
                                           "validUntil":null,"version":1}]}""", MediaType.APPLICATION_JSON));
        var product = client.fetchProduct(9001).orElseThrow();
        assertThat(product.version()).isEqualTo(3);
        assertThat(product.registrations()).hasSize(2);
        assertThat(product.registrations().get(0).validUntil()).isEqualTo(LocalDate.of(2029, 12, 31));
        assertThat(product.registrations().get(1).validUntil()).isNull();
        assertThat(product.registrations().get(1).version()).isEqualTo(1);
    }

    @Test
    void CASE_E173_anUnknownBatchIsEmpty() {
        server.expect(requestTo("http://catalog.test/internal/products/9001/batches/NOPE-0001")).andRespond(withResourceNotFound());
        assertThat(client.fetchBatch(9001, "NOPE-0001")).isEmpty();
    }

    static Stream<Arguments> unusable() {
        return Stream.of(
                argumentSet("CASE-E174 catalog down", withServerError()),
                argumentSet("CASE-E174 batch view without dates (incomplete payload)", withSuccess(
                        "{\"productId\":\"9001\",\"batchNo\":\"AZX2604-117\",\"plantCode\":\"IN-GOA\",\"version\":1}", MediaType.APPLICATION_JSON)),
                argumentSet("CASE-E174 batch view with a malformed id", withSuccess(
                        "{\"productId\":\"x\",\"batchNo\":\"AZX2604-117\",\"mfgDate\":\"2026-01-01\",\"expiryDate\":\"2027-01-01\","
                                + "\"plantCode\":\"IN-GOA\",\"version\":1}", MediaType.APPLICATION_JSON)));
    }

    @ParameterizedTest
    @MethodSource("unusable")
    void unusableAnswersAre503NeverA500(ResponseCreator response) {
        server.expect(requestTo("http://catalog.test/internal/products/9001/batches/AZX2604-117")).andRespond(response);
        assertThatThrownBy(() -> client.fetchBatch(9001, "AZX2604-117")).isInstanceOf(DependencyUnavailableException.class);
    }
}
