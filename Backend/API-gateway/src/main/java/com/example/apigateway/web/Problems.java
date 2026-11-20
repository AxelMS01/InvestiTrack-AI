package com.example.apigateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;

@Component
public class Problems {
    public static final String CORRELATION = "X-Correlation-ID";
    private final ObjectMapper json;

    public Problems(ObjectMapper json) { this.json = json; }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        var response = exchange.getResponse();
        if (response.isCommitted()) return Mono.empty();
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "urn:investitrack:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("code", code);
        body.put("correlationId", exchange.getAttributeOrDefault(CORRELATION, ""));
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        try {
            return response.writeWith(Mono.just(response.bufferFactory().wrap(json.writeValueAsBytes(body))));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }
}
