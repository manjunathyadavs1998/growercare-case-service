package com.growercare.casesvc.adapters.out.catalog;

import com.growercare.casesvc.application.port.CatalogDirectory;
import com.growercare.common.error.DependencyUnavailableException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Reads catalog-registry-service's internal views (SPEC-CAT-01 R20/R21) with case-service's client-credentials token.
 * 404 → empty; anything else that prevents an answer → 503 (SPEC-CASE-08 D7).
 */
public final class CatalogDirectoryClient implements CatalogDirectory {

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;

    public CatalogDirectoryClient(RestClient rest, OAuth2AuthorizedClientManager tokens) {
        this.rest = rest;
        this.tokens = tokens;
    }

    @Override
    public Optional<ProductReplica> fetchProduct(long productId) {
        return get("/internal/products/{id}", productId).map(CatalogDirectoryClient::product);
    }

    @Override
    public Optional<BatchReplica> fetchBatch(long productId, String batchNo) {
        return get("/internal/products/{id}/batches/{batchNo}", productId, batchNo).map(CatalogDirectoryClient::batch);
    }

    private Optional<JsonNode> get(String path, Object... vars) {
        try {
            return Optional.ofNullable(rest.get().uri(path, vars)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve().body(JsonNode.class));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException("catalog-registry-service", e);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException e) {
            throw new DependencyUnavailableException("catalog-registry-service", e);
        }
    }

    static ProductReplica product(JsonNode v) {
        try {
            var registrations = new ArrayList<RegistrationReplica>();
            for (var r : v.get("registrations")) {
                registrations.add(new RegistrationReplica(r.get("countryCode").asString(), r.get("registrationNo").asString(),
                        r.get("status").asString(), date(r, "validUntil"), r.get("version").asLong()));
            }
            return new ProductReplica(Long.parseLong(v.get("productId").asString()), v.get("productCode").asString(),
                    v.get("buCode").asString(), v.get("name").asString(), v.get("kind").asString(), v.get("version").asLong(),
                    registrations);
        } catch (RuntimeException e) {
            throw new DependencyUnavailableException("catalog-registry-service (unreadable product view)", e);
        }
    }

    static BatchReplica batch(JsonNode v) {
        try {
            return new BatchReplica(Long.parseLong(v.get("productId").asString()), v.get("batchNo").asString(),
                    date(v, "mfgDate"), date(v, "expiryDate"), v.get("plantCode").asString(), v.get("version").asLong());
        } catch (RuntimeException e) {
            throw new DependencyUnavailableException("catalog-registry-service (unreadable batch view)", e);
        }
    }

    private static LocalDate date(JsonNode v, String field) {
        var node = v.get(field);
        return node == null || node.isNull() ? null : LocalDate.parse(node.asString());
    }

    private String accessToken() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId("iam").principal("case-service").build();
        var client = tokens.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No client-credentials token for iam");
        }
        return client.getAccessToken().getTokenValue();
    }
}
