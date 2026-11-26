package com.example.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {
    static final RSAKey KEY;
    static final DisposableServer JWKS;
    static final Map<String, DisposableServer> SERVICES = new LinkedHashMap<>();
    static final int MANAGEMENT_PORT;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ISSUER = "https://issuer.example.test";
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            try (var socket = new ServerSocket(0)) { MANAGEMENT_PORT = socket.getLocalPort(); }
            JWKS = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                    response.header("Content-Type", "application/json")
                            .sendString(Mono.just(new JWKSet(KEY.toPublicJWK()).toString()))).bindNow();
            for (String owner : List.of("identity", "proposal", "project", "document", "reporting", "ai", "audit", "notification")) {
                SERVICES.put(owner, HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
                    if (request.requestHeaders().contains("X-Test-Disconnect")) {
                        request.withConnection(connection -> connection.dispose());
                        return Mono.empty();
                    }
                    if (request.path().endsWith("questions:stream")) {
                        return response.header("Content-Type", "text/event-stream").sendString(Mono.just("data: answer\n\n"));
                    }
                    var body = new LinkedHashMap<String, Object>();
                    body.put("owner", owner);
                    body.put("uri", request.uri());
                    body.put("method", request.method().name());
                    for (String header : List.of("Authorization", "X-Correlation-ID", "X-User-ID", "X-User-Roles",
                            "If-Match", "Idempotency-Key", "traceparent")) body.put(header, request.requestHeaders().get(header));
                    response.header("Content-Type", "application/json").header("ETag", "\"7\"")
                            .header("Location", "/api/v1/proposals/created");
                    String status = request.requestHeaders().get("X-Test-Status");
                    if (status != null) response.status(Integer.parseInt(status));
                    return request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("")
                            .flatMap(content -> {
                                body.put("body", content);
                                try { return response.sendString(Mono.just(JSON.writeValueAsString(body))).then(); }
                                catch (Exception error) { return Mono.error(error); }
                            });
                }).bindNow());
            }
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.issuer", () -> ISSUER);
        registry.add("gateway.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.port() + "/jwks");
        registry.add("gateway.max-request-bytes", () -> 64);
        registry.add("gateway.rate-limit.requests", () -> 1000);
        registry.add("management.server.port", () -> MANAGEMENT_PORT);
        SERVICES.forEach((owner, server) -> registry.add("gateway.services." + owner, () -> "http://127.0.0.1:" + server.port()));
        registry.add("gateway.disabled-owners", () -> "");
    }

    @LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void setup() { client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
    @AfterAll static void cleanup() { SERVICES.values().forEach(DisposableServer::disposeNow); JWKS.disposeNow(); }

    static String token(List<String> roles, String issuer, String audience, Instant expires, RSAKey key, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("institutional-subject")
                .issuer(issuer).audience(audience).issueTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(expires)).claim("roles", roles).claim("scope", scope).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
    static String token(String... roles) {
        try { return token(List.of(roles), ISSUER, "research-platform-api", Instant.now().plusSeconds(600), KEY, "research-platform"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static Stream<Arguments> routes() throws Exception {
        try (var stream = ApiGatewayApplicationTests.class.getResourceAsStream("/routes.tsv")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t")).map(fields -> Arguments.of(fields[0], fields[1], fields[2]))
                    .toList().stream();
        }
    }

    @ParameterizedTest(name = "{1} {2} -> {0}") @MethodSource("routes")
    void routesEveryDocumentedEndpoint(String owner, String method, String template) throws Exception {
        String path = template.replaceAll("\\{[^}]+}", "0e0d3f40-82e7-4db1-95e1-f123456789ab");
        var result = client.method(HttpMethod.valueOf(method)).uri(path)
                .headers(headers -> headers.setBearerAuth(token("ADMIN", "COORDINATOR", "RESEARCHER", "STUDENT", "EVALUATOR", "AUDITOR")))
                .exchange().expectStatus().isOk();
        if (path.endsWith("questions:stream")) result.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
        else result.expectBody().jsonPath("$.owner").isEqualTo(owner).jsonPath("$.uri").isEqualTo(path);
    }

    @Test void requiresBearerAuthentication() {
        client.get().uri("/api/v1/me").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectHeader().exists("X-Correlation-ID").expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }
    @Test void rejectsInvalidTokens() throws Exception {
        List<String> tokens = List.of("invalid", token(List.of(), "https://wrong-issuer", "research-platform-api", Instant.now().plusSeconds(600), KEY, ""),
                token(List.of(), ISSUER, "wrong-audience", Instant.now().plusSeconds(600), KEY, ""),
                token(List.of(), ISSUER, "research-platform-api", Instant.now().minusSeconds(600), KEY, ""),
                token(List.of(), ISSUER, "research-platform-api", Instant.now().plusSeconds(600), new RSAKeyGenerator(2048).generate(), ""));
        for (String token : tokens) client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.status").isEqualTo(401);
    }
    @Test void rejectsMissingClaimsAndFutureNotBefore() throws Exception {
        var base = new JWTClaimsSet.Builder().subject("subject").issuer(ISSUER).audience("research-platform-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var invalid = List.of(new JWTClaimsSet.Builder(base).audience((String) null).build(),
                new JWTClaimsSet.Builder(base).subject(null).build(),
                new JWTClaimsSet.Builder(base).expirationTime(null).build(),
                new JWTClaimsSet.Builder(base).notBeforeTime(Date.from(Instant.now().plusSeconds(600))).build());
        for (var claims : invalid) {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(jwt.serialize()))
                    .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        }
    }
    @Test void checksRolesWithoutImplicitAdminBypass() throws Exception {
        for (String path : List.of("/api/v1/roles", "/api/v1/users", "/api/v1/dashboards/coordinator", "/api/v1/audit-events")) {
            client.get().uri(path).headers(h -> h.setBearerAuth(token("STUDENT")))
                    .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        }
        client.post().uri("/api/v1/proposals").headers(h -> h.setBearerAuth(token("STUDENT"))).exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/users/lookup-id").headers(h -> h.setBearerAuth(token("RESEARCHER"))).exchange().expectStatus().isOk();
    }
    @Test void keepsInternalAndOperationalPathsOffPublicPort() throws Exception {
        for (String path : List.of("/internal/v1/authorization/check", "/actuator/health/liveness", "/actuator/prometheus", "/v3/api-docs", "/")) {
            client.get().uri(path).headers(h -> h.setBearerAuth(token("ADMIN"))).exchange().expectStatus().isNotFound();
        }
        client.post().uri("/api/v1/login").headers(h -> h.setBearerAuth(token())).exchange().expectStatus().isNotFound();
        client.post().uri("/api/v1/documents/abc/scan-results").headers(h -> h.setBearerAuth(token())).exchange().expectStatus().isNotFound();
    }
    @Test void managementRequiresMonitoringCredentialsExceptLiveness() throws Exception {
        var management = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + MANAGEMENT_PORT).build();
        management.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        management.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        management.get().uri("/actuator/health/readiness").headers(h -> h.setBearerAuth(token("ADMIN"))).exchange().expectStatus().isForbidden();
        String monitoring = token(List.of(), ISSUER, "research-platform-api", Instant.now().plusSeconds(600), KEY, "monitoring");
        management.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth(monitoring)).exchange().expectStatus().isOk();
        management.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(monitoring)).exchange().expectStatus().isNotFound();
    }
    @Test void forwardsContractsAndSanitizesIdentityHeaders() throws Exception {
        String bearer = token("RESEARCHER");
        client.patch().uri("/api/v1/proposals/abc?status=DRAFT&limit=25")
                .headers(h -> h.setBearerAuth(bearer)).header("X-Correlation-ID", "request-123")
                .header("X-User-ID", "forged").header("X-User-Roles", "ADMIN").header("If-Match", "\"6\"")
                .header("Idempotency-Key", "retry-123").header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"title\":\"Updated\"}")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Correlation-ID", "request-123")
                .expectHeader().valueEquals("ETag", "\"7\"").expectHeader().exists("Location")
                .expectBody().jsonPath("$.Authorization").isEqualTo("Bearer " + bearer)
                .jsonPath("$.X-User-ID").doesNotExist().jsonPath("$.X-User-Roles").doesNotExist()
                .jsonPath("$.X-Correlation-ID").isEqualTo("request-123").jsonPath("$.If-Match").isEqualTo("\"6\"")
                .jsonPath("$.Idempotency-Key").isEqualTo("retry-123")
                .jsonPath("$.uri").isEqualTo("/api/v1/proposals/abc?status=DRAFT&limit=25")
                .jsonPath("$.body").isEqualTo("{\"title\":\"Updated\"}");
    }
    @Test void replacesInvalidCorrelationIdentifiers() throws Exception {
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token())).header("X-Correlation-ID", "invalid spaces")
                .exchange().expectStatus().isOk().expectHeader().value("X-Correlation-ID", value -> assertThat(UUID.fromString(value)).isNotNull());
    }
    @Test void enforcesDeclaredAndChunkedBodyLimits() throws Exception {
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token())).bodyValue("x".repeat(65))
                .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("REQUEST_TOO_LARGE");
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token()))
                .body(reactor.core.publisher.Flux.just("x".repeat(40), "y".repeat(40)), String.class)
                .exchange().expectStatus().isEqualTo(413);
    }
    @Test void appliesCorsPolicy() {
        client.options().uri("/api/v1/me").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "PATCH").header("Access-Control-Request-Headers", "authorization,if-match")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
        client.options().uri("/api/v1/me").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin").expectBody().jsonPath("$.code").isEqualTo("CORS_REJECTED");
    }
    @Test void preservesDownstreamFailuresAndReportsUnavailableService() throws Exception {
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token())).header("X-Test-Status", "412")
                .exchange().expectStatus().isEqualTo(412).expectHeader().valueEquals("ETag", "\"7\"");
        client.post().uri("/api/v1/proposals").headers(h -> h.setBearerAuth(token("RESEARCHER"))).header("X-Test-Disconnect", "true")
                .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
}
