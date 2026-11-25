package com.example.proposalservice;

import com.example.proposalservice.api.*;
import com.example.proposalservice.infrastructure.IdentityClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class IdentityClientTests {
    HttpServer server;IdentityClient client;String base;UUID user;
    AtomicReference<String> response=new AtomicReference<>(),authorization=new AtomicReference<>();
    AtomicInteger status=new AtomicInteger(200),requests=new AtomicInteger();
    @BeforeEach void setup()throws Exception {
        user=UUID.randomUUID();response.set("{\"id\":\""+user+"\",\"status\":\"ACTIVE\",\"roles\":[\"RESEARCHER\"]}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");if(status.get()==302)exchange.getResponseHeaders().set("Location",base+"/redirected");exchange.sendResponseHeaders(status.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();client=new IdentityClient(new ObjectMapper(),base,"workload-credential",base);
    }
    @AfterEach void cleanup(){server.stop(0);SecurityContextHolder.clearContext();}
    Jwt token(){return Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("institutional-user").claim("roles",List.of("ADMIN")).build();}
    @Test void currentIdentityMappingAndRolesOverrideStaleTokenClaims() {
        var caller=client.resolve(token());assertThat(caller.id()).isEqualTo(user);assertThat(caller.researcher()).isTrue();assertThat(caller.administrator()).isFalse();assertThat(authorization.get()).isEqualTo("Bearer signed-user-token");
    }
    @Test void malformedInactiveAndConflictingMappingsFailClosed() {
        response.set("{}");assertThatThrownBy(()->client.resolve(token())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        response.set("{\"status\":\"DEACTIVATED\"}");assertThatThrownBy(()->client.resolve(token())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        response.set("{\"id\":\""+user+"\",\"status\":\"ACTIVE\",\"roles\":[\"RESEARCHER\"]}");
        var forged=Jwt.withTokenValue("signed-token").header("alg","RS256").subject("institutional-user").claim("user_id",UUID.randomUUID().toString()).build();assertThatThrownBy(()->client.resolve(forged)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("USER_MAPPING_MISMATCH");
    }
    @Test void statusQueriesRequireWorkloadCredentialsAndRequestedInstitutionalRole() {
        response.set("{\"userId\":\""+user+"\",\"active\":true,\"roles\":[\"EVALUATOR\"]}");client.active(user,"EVALUATOR");assertThat(authorization.get()).isEqualTo("Bearer workload-credential");
        assertThatThrownBy(()->client.active(user,"COORDINATOR")).isInstanceOf(ApiException.class).extracting("status").isEqualTo(422);
        assertThatThrownBy(()->client.active(UUID.randomUUID(),null)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
    }
    @Test void documentsMustBeAvailableAndActuallyBelongToTheProposal() {
        UUID proposal=UUID.randomUUID(),document=UUID.randomUUID();SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token()));
        response.set("{\"id\":\""+document+"\",\"status\":\"AVAILABLE\",\"context\":{\"proposalId\":\""+proposal+"\"}}");client.documents(proposal,List.of(document));assertThat(authorization.get()).isEqualTo("Bearer signed-user-token");
        assertThatThrownBy(()->client.documents(UUID.randomUUID(),List.of(document))).isInstanceOf(ApiException.class).extracting("status").isEqualTo(422);
    }
    @Test void unavailableDependenciesAndRedirectsAreNotRetried() {
        status.set(503);assertThatThrownBy(()->client.resolve(token())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);assertThat(requests.get()).isEqualTo(1);
        status.set(302);assertThatThrownBy(()->client.resolve(token())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);assertThat(requests.get()).isEqualTo(2);
    }
}
