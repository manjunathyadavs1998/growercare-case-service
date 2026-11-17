package com.growercare.casesvc.adapters.out.grower;

import com.growercare.casesvc.application.port.FarmDirectory;
import com.growercare.common.error.DependencyUnavailableException;
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
 * Reads grower-service's {@code GET /internal/growers/{growerId}/farms/{farmId}} (SPEC-GRW-02 R213) with case-service's
 * client-credentials token. 404 → empty; anything else that prevents an answer → 503 (SPEC-CASE-08 D7).
 */
public final class FarmDirectoryClient implements FarmDirectory {

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;

    public FarmDirectoryClient(RestClient rest, OAuth2AuthorizedClientManager tokens) {
        this.rest = rest;
        this.tokens = tokens;
    }

    @Override
    public Optional<FarmReplica> fetchFarm(long growerId, long farmId) {
        try {
            var body = rest.get()
                    .uri("/internal/growers/{growerId}/farms/{farmId}", growerId, farmId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(body).map(FarmDirectoryClient::farm);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException("grower-service", e);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException | IllegalArgumentException
                 | NullPointerException | ArithmeticException e) {   // network, token, unreadable or incomplete body
            throw new DependencyUnavailableException("grower-service", e);
        }
    }

    static FarmReplica farm(JsonNode v) {
        var location = v.get("location");
        boolean hasPoint = location != null && !location.isNull();
        return new FarmReplica(Long.parseLong(v.get("farmId").asString()), Long.parseLong(v.get("growerId").asString()),
                v.get("name").asString(), v.get("areaHa").decimalValue(),
                hasPoint ? location.get("latitude").decimalValue() : null, hasPoint ? location.get("longitude").decimalValue() : null,
                v.get("status").asString(), v.get("version").asInt());
    }

    /** case-service's own Keycloak client ("iam" registration, historical name). */
    private String accessToken() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId("iam").principal("case-service").build();
        var client = tokens.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No client-credentials token for iam");
        }
        return client.getAccessToken().getTokenValue();
    }
}
