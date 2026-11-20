package com.example.apigateway.config;

import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Configuration
public class RoutingConfiguration {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties properties) throws IOException {
        var routes = builder.routes();
        // Explicit method/path allowlist: future internal callbacks cannot accidentally become public.
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("routes.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            int index = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t");
                String owner = fields[0];
                var uri = properties.services().get(owner);
                if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException("Missing or invalid service URI for " + owner);
                }
                routes.route(owner + "-" + index++, route -> {
                    var predicate = route.path(fields[2]).and().method(HttpMethod.valueOf(fields[1]));
                    if (owner.equals("ai")) predicate.metadata("response-timeout", 120000L);
                    return predicate.uri(uri);
                });
            }
        }
        return routes.build();
    }
}
