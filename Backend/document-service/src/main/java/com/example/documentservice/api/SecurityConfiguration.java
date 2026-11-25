package com.example.documentservice.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
        @Value("${document.security.audience}") String audience,
        @Value("${document.security.internal-audience}") String internal) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> required=jwt->jwt.getAudience()!=null && jwt.getAudience().stream().anyMatch(a->a.equals(audience)||a.equals(internal))
            && jwt.getSubject()!=null&&!jwt.getSubject().isBlank()&&jwt.getExpiresAt()!=null
            ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required token claims are invalid",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),required));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper json,
        @Value("${document.security.audience}") String audience,
        @Value("${document.security.internal-audience}") String internal,
        @Value("${document.security.workload-clients}") String clients) throws Exception {
        var allowed=new HashSet<>(Arrays.asList(clients.split(",")));
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health/liveness").permitAll()
                .requestMatchers("/internal/v1/documents/*/access-context").hasAuthority("SCOPE_document:context")
                .requestMatchers("/internal/v1/documents/*/scan-results").hasAuthority("SCOPE_document:scan")
                .requestMatchers("/internal/v1/documents/*/extractions").hasAuthority("SCOPE_document:extract")
                .requestMatchers("/internal/v1/documents/*/indexing-content","/internal/v1/documents/*/indexing-status").hasAuthority("SCOPE_document:index")
                .requestMatchers("/internal/v1/documents/*/source-validation").hasAuthority("SCOPE_document:source")
                .requestMatchers("/internal/v1/documents/*/reference-validation","/internal/v1/documents/*/holds/*").hasAuthority("SCOPE_document:reference")
                .requestMatchers("/internal/v1/documents/*/processing-jobs/*/content").hasAuthority("SCOPE_document:process")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/actuator/**","/v3/api-docs").hasAuthority("SCOPE_monitoring")
                .anyRequest().denyAll())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
                    String correlation=r.getHeader("X-Correlation-ID");
                    if(correlation==null||!correlation.matches("[A-Za-z0-9._:-]{1,100}")) correlation=UUID.randomUUID().toString();
                    r.setAttribute("correlationId",correlation);s.setHeader("X-Correlation-ID",correlation);chain.doFilter(r,s);
                }
            },SecurityContextHolderFilter.class)
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
                    var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if(authentication instanceof JwtAuthenticationToken token) {
                        var jwt=token.getToken(); boolean workload=r.getRequestURI().startsWith("/internal/");
                        String client=jwt.getClaimAsString("client_id");
                        boolean valid=jwt.getAudience()!=null&&jwt.getAudience().contains(workload?internal:audience);
                        if(workload) {
                            String path=r.getRequestURI();
                            boolean delegated=path.endsWith("/source-validation")||path.endsWith("/reference-validation")||(path.contains("/holds/")&&r.getMethod().equals("PUT"));
                            valid=valid&&client!=null&&allowed.contains(client)&&(delegated?jwt.getClaimAsString("user_id")!=null&&!client.equals(jwt.getSubject()):client.equals(jwt.getSubject()));
                            String expected=path.endsWith("/access-context")?"identity-service":path.endsWith("/scan-results")?"malware-scanner":path.endsWith("/extractions")?"text-extractor":path.contains("/holds/")||path.endsWith("/reference-validation")?"project-service":"ai-service";
                            if(path.contains("/processing-jobs/"))valid=valid&&Set.of("malware-scanner","text-extractor").contains(client);
                            else valid=valid&&(expected.equals("project-service")?Set.of("project-execution-service","project-service").contains(client):expected.equals(client));
                        }
                        if(!valid) { failure(json,r,s,403,"TOKEN_CONTEXT_NOT_ALLOWED");return; }
                    }
                    chain.doFilter(r,s);
                }
            },AnonymousAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->failure(json,r,s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((r,s,x)->failure(json,r,s,403,"FORBIDDEN")))
            .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((r,s,x)->failure(json,r,s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((r,s,x)->failure(json,r,s,403,"FORBIDDEN"))).build();
    }
    static void failure(ObjectMapper json,HttpServletRequest r,HttpServletResponse s,int status,String code) throws IOException {
        s.setStatus(status);s.setContentType("application/problem+json");
        if(status==401)s.setHeader("WWW-Authenticate","Bearer");
        s.getWriter().write(json.writeValueAsString(Problems.response(status,code,status==401?"A valid bearer token is required.":"Access is denied.",r).getBody()));
    }
}
