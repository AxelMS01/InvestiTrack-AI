package com.example.apigateway.config;

import com.example.apigateway.web.Problems;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.web.server.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.*;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class SecurityConfiguration {
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> required = token -> token.getAudience() != null && token.getAudience().contains(properties.audience())
                && token.getSubject() != null && !token.getSubject().isBlank() && token.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required claims are missing or invalid", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), required));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, Problems problems,
                                    GatewayProperties properties) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        var scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var granted = new java.util.ArrayList<>(scopes.convert(jwt));
            granted.addAll(authorities.convert(jwt));
            return granted;
        });
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "If-Match", "If-None-Match",
                "Idempotency-Key", Problems.CORRELATION, "traceparent", "tracestate", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("ETag", "Location", Problems.CORRELATION, "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        var processor = new org.springframework.web.cors.reactive.DefaultCorsProcessor();
        org.springframework.web.server.WebFilter corsFilter = (exchange, chain) -> {
            if (!processor.process(source.getCorsConfiguration(exchange), exchange)) {
                return problems.write(exchange, HttpStatus.FORBIDDEN, "CORS_REJECTED", "The cross-origin request is not allowed.");
            }
            if (org.springframework.web.cors.reactive.CorsUtils.isPreFlightRequest(exchange.getRequest())) {
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .addFilterAt(corsFilter, SecurityWebFiltersOrder.CORS)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/liveness").permitAll()
                        .pathMatchers("/actuator/**").hasAuthority("SCOPE_monitoring")
                        .pathMatchers("/api/v1/roles", "/api/v1/roles/**", "/api/v1/users/*/roles", "/api/v1/users/*/roles/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/users").hasAnyRole("ADMIN", "COORDINATOR")
                        .pathMatchers(HttpMethod.GET, "/api/v1/users/*").authenticated()
                        .pathMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/me/evaluation-assignments").hasRole("EVALUATOR")
                        .pathMatchers("/api/v1/evaluation-criteria", "/api/v1/evaluation-criteria/**").hasAnyRole("EVALUATOR", "COORDINATOR")
                        .pathMatchers(HttpMethod.POST, "/api/v1/proposals").hasRole("RESEARCHER")
                        .pathMatchers(HttpMethod.POST, "/api/v1/projects").hasRole("COORDINATOR")
                        .pathMatchers(HttpMethod.POST, "/api/v1/proposals/*/evaluations", "/api/v1/proposals/*/evaluations/*/submission").hasRole("EVALUATOR")
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/proposals/*/evaluations/*").hasRole("EVALUATOR")
                        .pathMatchers(HttpMethod.POST, "/api/v1/projects/*/progress/recalculation").hasAnyRole("COORDINATOR", "ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/proposals/*/evaluator-assignments", "/api/v1/proposals/*/evaluations/*/release").hasRole("COORDINATOR")
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/proposals/*/evaluator-assignments/*").hasRole("COORDINATOR")
                        .pathMatchers(HttpMethod.DELETE, "/api/v1/proposals/*/evaluator-assignments/*").hasRole("COORDINATOR")
                        .pathMatchers("/api/v1/dashboards/coordinator").hasRole("COORDINATOR")
                        .pathMatchers("/api/v1/dashboards/evaluator").hasRole("EVALUATOR")
                        .pathMatchers("/api/v1/dashboards/researcher").hasAnyRole("RESEARCHER", "STUDENT")
                        .pathMatchers("/api/v1/audit-events", "/api/v1/audit-events/**", "/api/v1/audit-export-jobs", "/api/v1/audit-export-jobs/**").hasAnyRole("ADMIN", "AUDITOR")
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, error) -> {
                            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            return problems.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required.");
                        })
                        .accessDeniedHandler((exchange, error) -> problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied.")))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, error) -> {
                            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            return problems.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required.");
                        }))
                .addFilterAfter(new com.example.apigateway.web.RequestPolicyFilter(properties, problems, java.time.Clock.systemUTC()), SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }
}
