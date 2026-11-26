package com.example.apigateway.config;

import com.example.apigateway.web.Problems;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Configuration
public class RoutingConfiguration {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties properties, Problems problems) throws IOException {
        var routes = builder.routes();
        // Explicit method/path allowlist: future internal callbacks cannot accidentally become public.
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("routes.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            int index = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t");
                String owner = fields[0];
                var method = HttpMethod.valueOf(fields[1]);
                var routeId = owner + "-" + index++;
                if (properties.disabledOwners() != null && properties.disabledOwners().contains(owner)) {
                    routes.route(routeId, route -> route.path(fields[2]).and().method(method)
                            .filters(filters -> filters.filter((exchange, chain) -> problems.write(exchange,
                                    HttpStatus.SERVICE_UNAVAILABLE, "FEATURE_UNAVAILABLE",
                                    "This feature is disabled in this deployment.")))
                            .uri("no://op"));
                    continue;
                }
                var uri = properties.services().get(owner);
                if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException("Missing or invalid service URI for " + owner);
                }
                routes.route(routeId, route -> {
                    var predicate = route.path(fields[2]).and().method(method);
                    if (owner.equals("ai")) predicate.metadata("response-timeout", 120000L);
                    return predicate.uri(uri);
                });
            }
        }
        return routes.build();
    }
}
