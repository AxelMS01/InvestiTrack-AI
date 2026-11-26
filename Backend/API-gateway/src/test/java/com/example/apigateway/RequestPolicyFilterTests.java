package com.example.apigateway;

import com.example.apigateway.config.GatewayProperties;
import com.example.apigateway.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPolicyFilterTests {
    @Test void limitsPerSubjectBoundsMemoryAndRecoversAfterWindowExpires() {
        var now = new AtomicLong(1000);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var properties = new GatewayProperties("issuer", "jwks", "audience", List.of("http://localhost"), 100,
                new GatewayProperties.RateLimit(2, 60, 2), java.util.Set.of(),
                Map.of("identity", URI.create("http://localhost")));
        var policy = new RequestPolicyFilter(properties, new Problems(new ObjectMapper()), clock);
        assertThat(request(policy, "alice").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(policy, "alice").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        var rejected = request(policy, "alice");
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(request(policy, "bob").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(policy, "carol").getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        now.addAndGet(60000);
        assertThat(request(policy, "carol").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(policy, "alice").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private MockServerWebExchange request(RequestPolicyFilter policy, String subject) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/me").build());
        var authenticated = exchange.mutate().principal(Mono.just(new TestingAuthenticationToken(subject, null))).build();
        policy.filter(authenticated, next -> {
            next.getResponse().setStatusCode(HttpStatus.OK);
            return next.getResponse().setComplete();
        }).block();
        return exchange;
    }
}
