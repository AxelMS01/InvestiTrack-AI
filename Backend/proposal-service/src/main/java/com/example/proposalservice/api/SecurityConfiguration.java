package com.example.proposalservice.api;

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
        @Value("${proposal.security.audience}") String audience,
        @Value("${proposal.security.internal-audience}") String internal) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> required=jwt->jwt.getAudience()!=null && jwt.getAudience().stream().anyMatch(a->a.equals(audience)||a.equals(internal))
            && jwt.getSubject()!=null&&!jwt.getSubject().isBlank()&&jwt.getExpiresAt()!=null
            ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required token claims are invalid",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),required));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper json,com.example.proposalservice.infrastructure.IdentityClient identity,
        @Value("${proposal.security.audience}") String audience,
        @Value("${proposal.security.internal-audience}") String internal,
        @Value("${proposal.security.workload-clients}") String clients) throws Exception {
        var allowed=new HashSet<>(Arrays.asList(clients.split(",")));
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health/liveness").permitAll()
                .requestMatchers("/internal/v1/proposals/*/project-creation-data","/internal/v1/proposals/*/project-reference").hasAuthority("SCOPE_proposal:project-integration")
                .requestMatchers("/internal/v1/proposals/*/access-context").hasAuthority("SCOPE_proposal:context")
                .requestMatchers("/internal/v1/projection-state/**","/internal/v1/projection-snapshots").hasAuthority("SCOPE_proposal:projection")
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
                            valid=valid&&client!=null&&allowed.contains(client)&&client.equals(jwt.getSubject());
                            if(r.getRequestURI().startsWith("/internal/v1/projection"))valid=valid&&"reporting-service".equals(client);
                        }
                        if(workload&&r.getRequestURI().endsWith("/access-context"))valid=valid&&Set.of("identity-service","document-service").contains(client);
                        if(workload&&(r.getRequestURI().endsWith("/project-creation-data")||r.getRequestURI().endsWith("/project-reference")))valid=valid&&"project-execution-service".equals(client);
                        if(!valid) { failure(json,r,s,403,"TOKEN_CONTEXT_NOT_ALLOWED");return; }
                        if(r.getRequestURI().startsWith("/api/v1/")) {
                            try{r.setAttribute(Caller.ATTRIBUTE,identity.resolve(jwt));}
                            catch(ApiException e){write(json,r,s,e.status(),e.code(),e.getMessage());return;}
                        }
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
        write(json,r,s,status,code,status==401?"A valid bearer token is required.":"Access is denied.");
    }
    static void write(ObjectMapper json,HttpServletRequest r,HttpServletResponse s,int status,String code,String detail)throws IOException {
        s.setStatus(status);s.setContentType("application/problem+json");if(status==401)s.setHeader("WWW-Authenticate","Bearer");
        s.getWriter().write(json.writeValueAsString(ApiExceptionHandler.problem(status,code,detail,r).getBody()));
    }
}
