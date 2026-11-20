package com.example.identityservice.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${identity.security.audience}") String audience,
        @Value("${identity.security.internal-audience}") String internalAudience,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
        var decoder=jwkSetUri.isBlank() ? JwtDecoders.<NimbusJwtDecoder>fromIssuerLocation(issuer)
            : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audiences=jwt -> jwt.getAudience()!=null && jwt.getAudience().stream().anyMatch(a -> a.equals(audience)||a.equals(internalAudience))
            ? OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Token audience is not accepted",null));
        OAuth2TokenValidator<Jwt> requiredClaims=jwt -> jwt.getSubject()!=null && !jwt.getSubject().isBlank() && jwt.getExpiresAt()!=null
            ? OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Subject and expiration are required",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),audiences,requiredClaims));
        return decoder;
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json,
        @Value("${identity.security.audience}") String audience,
        @Value("${identity.security.internal-audience}") String internalAudience) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/liveness").permitAll()
                .requestMatchers("/internal/v1/authorization/check").hasAuthority("SCOPE_identity:check")
                .requestMatchers("/internal/v1/authorization/filter").hasAuthority("SCOPE_identity:filter")
                .requestMatchers("/internal/v1/users/*/status").hasAuthority("SCOPE_identity:status")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/actuator/**","/v3/api-docs/**").hasAuthority("SCOPE_monitoring")
                .anyRequest().denyAll())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
                    String correlation=r.getHeader("X-Correlation-ID");
                    if(correlation==null||!correlation.matches("[A-Za-z0-9._:-]{1,100}")) correlation=UUID.randomUUID().toString();
                    r.setAttribute("correlationId",correlation); s.setHeader("X-Correlation-ID",correlation);
                    chain.doFilter(r,s);
                }
            }, org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
                    var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if(auth instanceof JwtAuthenticationToken token) {
                        String expected=r.getRequestURI().startsWith("/internal/")?internalAudience:audience;
                        if(token.getToken().getAudience()==null || !token.getToken().getAudience().contains(expected)) { failure(json,r,s,403,"INVALID_AUDIENCE"); return; }
                    }
                    chain.doFilter(r,s);
                }
            }, AnonymousAuthenticationFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x)->failure(json,r,s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((r,s,x)->failure(json,r,s,403,"FORBIDDEN")))
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((r,s,x)->failure(json,r,s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((r,s,x)->failure(json,r,s,403,"FORBIDDEN")))
            .build();
    }
    private static void failure(ObjectMapper json,HttpServletRequest r,HttpServletResponse s,int status,String code) throws IOException {
        s.setStatus(status); s.setContentType("application/problem+json");
        if(status==401) s.setHeader("WWW-Authenticate","Bearer");
        s.getWriter().write(json.writeValueAsString(Problems.problem(status,code,status==401?"A valid bearer token is required.":"Access is denied.",r).getBody()));
    }
}
