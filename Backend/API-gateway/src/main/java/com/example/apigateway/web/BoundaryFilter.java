package com.example.apigateway.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class BoundaryFilter implements WebFilter, Ordered {
    private final Problems problems;
    private final int managementPort;

    public BoundaryFilter(Problems problems, @Value("${management.server.port}") int managementPort) {
        this.problems = problems;
        this.managementPort = managementPort;
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var ids = exchange.getRequest().getHeaders().get(Problems.CORRELATION);
        String id = ids != null && ids.size() == 1 && ids.getFirst().matches("[A-Za-z0-9._:-]{1,100}")
                ? ids.getFirst() : UUID.randomUUID().toString();
        exchange.getAttributes().put(Problems.CORRELATION, id);
        exchange.getResponse().getHeaders().set(Problems.CORRELATION, id);
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.set(Problems.CORRELATION, id);
            // Identity is established only by the signed bearer token. Never trust client assertions.
            List.of("X-User-ID", "X-User-Roles", "X-Roles", "X-Authenticated-User", "X-Auth-Request-User",
                    "X-Forwarded-User", "X-Forwarded-Roles", "Forwarded", "X-Forwarded-For",
                    "X-Forwarded-Host", "X-Forwarded-Port", "X-Forwarded-Proto", "X-Real-IP")
                    .forEach(headers::remove);
        }).build();
        var sanitized = exchange.mutate().request(request).build();
        String path = request.getPath().value();
        boolean management = request.getLocalAddress() != null
                && request.getLocalAddress().getPort() == managementPort;
        if (management ? !path.startsWith("/actuator/") : !path.startsWith("/api/v1/")) {
            return problems.write(sanitized, HttpStatus.NOT_FOUND, "NOT_FOUND", "No public endpoint matches this path.");
        }
        return chain.filter(sanitized);
    }
}
