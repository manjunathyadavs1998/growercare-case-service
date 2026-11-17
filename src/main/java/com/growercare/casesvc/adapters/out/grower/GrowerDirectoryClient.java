package com.growercare.casesvc.adapters.out.grower;

import com.growercare.casesvc.application.port.GrowerDirectory;
import com.growercare.common.error.DependencyUnavailableException;
import java.util.Base64;
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
 * Reads grower-service's {@code GET /internal/growers/{id}} (SPEC-GRW-01 R42) with case-service's client-credentials
 * token (Keycloak client {@code case-service}, audience {@code gc-internal}; the same token as for IAM).
 * 404 → empty; anything else that prevents an answer → {@link DependencyUnavailableException} (fail closed, SPEC-CASE-06 R142).
 */
public final class GrowerDirectoryClient implements GrowerDirectory {

    static final String REGISTRATION = "iam";   // case-service's own Keycloak client; the registration name is historical

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;

    public GrowerDirectoryClient(RestClient rest, OAuth2AuthorizedClientManager tokens) {
        this.rest = rest;
        this.tokens = tokens;
    }

    @Override
    public Optional<GrowerReplica> fetch(long growerId) {
        try {
            var body = rest.get()
                    .uri("/internal/growers/{id}", growerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(body).map(GrowerDirectoryClient::replica);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException("grower-service", e);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException | IllegalArgumentException
                 | NullPointerException e) {   // network, token, unreadable or incomplete body: fail closed
            throw new DependencyUnavailableException("grower-service", e);
        }
    }

    /** The view's ids are strings (SPEC-GRW-01 §3); optional fields may be absent or null. */
    static GrowerReplica replica(JsonNode v) {
        return new GrowerReplica(
                Long.parseLong(v.get("growerId").asString()),
                v.get("countryCode").asString(),
                Long.parseLong(v.get("territoryId").asString()),
                optionalLong(v, "userId"),
                v.get("displayName").asString(),
                text(v, "phoneHmac") == null ? null : Base64.getDecoder().decode(text(v, "phoneHmac")),
                text(v, "phoneLast4"),
                optionalLong(v, "preferredPartnerOrgId"),
                v.get("status").asString(),
                v.get("version").asInt());
    }

    private static String text(JsonNode v, String field) {
        var node = v.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Long optionalLong(JsonNode v, String field) {
        var raw = text(v, field);
        return raw == null ? null : Long.parseLong(raw);
    }

    private String accessToken() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION).principal("case-service").build();
        var client = tokens.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No client-credentials token for " + REGISTRATION);
        }
        return client.getAccessToken().getTokenValue();
    }
}
