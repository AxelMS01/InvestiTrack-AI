package com.example.proposalservice.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, tools.jackson.databind.ObjectMapper json) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/liveness").permitAll()
                .requestMatchers("/internal/v1/proposals/**").hasAuthority("SCOPE_proposal:project-integration")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/actuator/**").hasAuthority("SCOPE_monitoring")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> securityProblem(json,request,response,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((request,response,error) -> securityProblem(json,request,response,403,"FORBIDDEN")))
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((request,response,error) -> securityProblem(json,request,response,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((request,response,error) -> securityProblem(json,request,response,403,"FORBIDDEN")))
            .build();
    }
    private static void securityProblem(tools.jackson.databind.ObjectMapper json,
        jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,int status,String code) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        if(status==401) response.setHeader("WWW-Authenticate","Bearer");
        String correlation=request.getHeader("X-Correlation-ID");
        response.getWriter().write(json.writeValueAsString(java.util.Map.of(
            "type","urn:proposal-service:problem:"+code.toLowerCase(),"title",code,"status",status,
            "detail",status==401?"A valid bearer token is required.":"Access is denied.",
            "instance",request.getRequestURI(),"code",code,
            "correlationId",correlation==null?java.util.UUID.randomUUID().toString():correlation)));
    }
}
