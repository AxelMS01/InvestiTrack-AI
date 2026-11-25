package com.example.apigateway.web;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private final Problems problems;
    public GatewayErrorHandler(Problems problems) { this.problems = problems; }

    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        if (error instanceof ResponseStatusException statusException) {
            var status = HttpStatus.resolve(statusException.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return problems.write(exchange, status, status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "INVALID_REQUEST",
                        status == HttpStatus.NOT_FOUND ? "No public endpoint matches this request." : "The request could not be processed.");
            }
        }
        // Hide DNS names, connection details and stack traces from clients. Do not retry mutations.
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The requested service is temporarily unavailable.");
    }
}
