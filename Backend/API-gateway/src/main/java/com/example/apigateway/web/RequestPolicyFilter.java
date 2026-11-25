package com.example.apigateway.web;

import com.example.apigateway.config.GatewayProperties;
import org.springframework.core.io.buffer.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.*;
import reactor.core.publisher.*;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Runs inside the security chain, after authentication and coarse authorization. */
public class RequestPolicyFilter implements WebFilter {
    private final GatewayProperties properties;
    private final Problems problems;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long start, int used) { }

    public RequestPolicyFilter(GatewayProperties properties, Problems problems, Clock clock) {
        this.properties = properties;
        this.problems = problems;
        this.clock = clock;
    }

    private synchronized long retryAfter(String subject) {
        long now = clock.millis();
        long duration = properties.rateLimit().windowSeconds() * 1000L;
        windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= duration);
        Window window = windows.get(subject);
        if (window == null) {
            // Bound memory even during subject churn. Saturation fails closed.
            if (windows.size() >= properties.rateLimit().maxSubjects()) return properties.rateLimit().windowSeconds();
            windows.put(subject, new Window(now, 1));
            return 0;
        }
        if (window.used() >= properties.rateLimit().requests()) return Math.max(1, (duration - (now - window.start()) + 999) / 1000);
        windows.put(subject, new Window(window.start(), window.used() + 1));
        return 0;
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/")) return chain.filter(exchange);
        return exchange.<Authentication>getPrincipal().flatMap(principal -> {
            long retry = retryAfter(principal.getName());
            if (retry > 0) {
                exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retry));
                return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Request rate limit exceeded.");
            }
            int max = properties.maxRequestBytes();
            if (exchange.getRequest().getHeaders().getContentLength() > max) return tooLarge(exchange);
            // Check actual bytes too, including bodies using chunked transfer encoding.
            return DataBufferUtils.join(exchange.getRequest().getBody(), max)
                    .map(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    })
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> {
                        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override public Flux<DataBuffer> getBody() {
                                return bytes.length == 0 ? Flux.empty() : Flux.defer(() ->
                                        Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                            }
                        };
                        return chain.filter(exchange.mutate().request(request).build());
                    })
                    .onErrorResume(DataBufferLimitException.class, error -> tooLarge(exchange));
        });
    }

    private Mono<Void> tooLarge(ServerWebExchange exchange) {
        return problems.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Request exceeds the configured size limit.");
    }
}
