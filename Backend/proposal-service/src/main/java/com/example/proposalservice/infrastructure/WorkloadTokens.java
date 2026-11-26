package com.example.proposalservice.infrastructure;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class WorkloadTokens {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String endpoint;
    private final String authorization;
    private String token;
    private Instant refreshAfter = Instant.EPOCH;

    WorkloadTokens(ObjectMapper json, @Value("${WORKLOAD_TOKEN_URI:}") String endpoint,
                   @Value("${WORKLOAD_CLIENT_SECRET:}") String secret) {
        this.json = json;
        this.endpoint = endpoint;
        String credential = "proposal-service:" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        this.authorization = secret.isBlank() ? "" :
            "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    synchronized String accessToken() {
        if (Instant.now().isBefore(refreshAfter)) return token;
        if (endpoint.isBlank() || authorization.isBlank()) throw IdentityClient.unavailable();
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(4))
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw IdentityClient.unavailable();
            var body = json.readTree(response.body());
            String issuedToken = body.path("access_token").asString("");
            int lifetime = body.path("expires_in").asInt(0);
            if (issuedToken.isBlank() || lifetime < 1) throw IdentityClient.unavailable();
            token = issuedToken;
            refreshAfter = Instant.now().plusSeconds(Math.max(1, lifetime - 15));
            return token;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw IdentityClient.unavailable();
        } catch (Exception error) {
            throw IdentityClient.unavailable();
        }
    }
}
