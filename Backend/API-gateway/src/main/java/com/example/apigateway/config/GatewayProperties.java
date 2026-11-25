package com.example.apigateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience,
        @NotEmpty List<String> allowedOrigins,
        @Min(1) int maxRequestBytes,
        @Valid RateLimit rateLimit,
        @NotEmpty Map<String, URI> services) {
    public record RateLimit(@Min(1) int requests, @Min(1) int windowSeconds, @Min(1) int maxSubjects) { }
}
