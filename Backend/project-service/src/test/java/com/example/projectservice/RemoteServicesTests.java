package com.example.projectservice;

import com.example.projectservice.api.*;
import com.example.projectservice.api.ApiModels.Caller;
import com.example.projectservice.infrastructure.RemoteServices;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class RemoteServicesTests {
    HttpServer server;
    RemoteServices remote;
    String base;
    UUID user;
    AtomicReference<String> profile=new AtomicReference<>();
    AtomicReference<String> authorization=new AtomicReference<>();
    AtomicReference<String> body=new AtomicReference<>();
    AtomicReference<String> path=new AtomicReference<>();
    AtomicInteger code=new AtomicInteger(200),requests=new AtomicInteger();
    @BeforeEach void setup()throws Exception {
        user=UUID.randomUUID();profile.set("{\"id\":\""+user+"\",\"status\":\"ACTIVE\",\"roles\":[\"RESEARCHER\"]}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{
            requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));path.set(exchange.getRequestURI().getPath());
            String result=profile.get();
            if(path.get().equals("/token"))result="{\"access_token\":\"delegated-token\"}";
            if(path.get().endsWith("/reference-validation"))result="{\"valid\":true,\"documentVersion\":2,\"sha256\":\""+"a".repeat(64)+"\"}";
            if(path.get().endsWith("/status"))result="{\"userId\":\""+user+"\",\"active\":true}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            if(code.get()==302)exchange.getResponseHeaders().set("Location",base+"/redirected");
            exchange.sendResponseHeaders(code.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        remote=new RemoteServices(new ObjectMapper(),base,base,base,"identity-workload","proposal-workload","document-workload",base+"/token","project-execution-service","exchange-secret","document-service");
    }
    @AfterEach void stop(){server.stop(0);}
    Jwt jwt(){return Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("institutional-subject").claim("roles",List.of("ADMIN")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();}
    @Test void callerUsesLiveIdentityMappingAndRolesRatherThanTokenRoleAssertions() {
        var caller=remote.caller(jwt());assertThat(caller.id()).isEqualTo(user);assertThat(caller.roles()).containsExactly("RESEARCHER");assertThat(authorization.get()).isEqualTo("Bearer signed-user-token");
    }
    @Test void missingInactiveAndConflictingMappingsFailClosed() {
        profile.set("{\"id\":\""+user+"\",\"status\":\"DEACTIVATED\",\"roles\":[\"ADMIN\"]}");
        assertThatThrownBy(()->remote.caller(jwt())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        profile.set("{}");assertThatThrownBy(()->remote.caller(jwt())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        profile.set("{\"id\":\""+user+"\",\"status\":\"ACTIVE\",\"roles\":[\"RESEARCHER\"]}");
        var token=Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("institutional-subject").claim("user_id",UUID.randomUUID().toString()).build();
        assertThatThrownBy(()->remote.caller(token)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("USER_MAPPING_MISMATCH");
    }
    @Test void accountStatusUsesWorkloadCredentialsAndChecksReturnedUser() {
        remote.activeUser(user);assertThat(authorization.get()).isEqualTo("Bearer identity-workload");
        assertThatThrownBy(()->remote.activeUser(UUID.randomUUID())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
    }
    @Test void documentValidationUsesAudienceLimitedTokenExchangeAndPinnedVersion() {
        UUID project=UUID.randomUUID(),document=UUID.randomUUID();var validation=remote.validateDocument(project,document,2,"FINAL_REPORT",new Caller(user,Set.of("RESEARCHER"),"signed-user-token"));
        assertThat(requests.get()).isEqualTo(2);assertThat(authorization.get()).isEqualTo("Bearer delegated-token");assertThat(path.get()).isEqualTo("/internal/v1/documents/"+document+"/reference-validation");
        assertThat(body.get()).contains(project.toString()).contains("\"documentVersion\":2").doesNotContain("signed-user-token");assertThat(validation.get("sha256")).isEqualTo("a".repeat(64));
        assertThatThrownBy(()->remote.validateDocument(project,document,1,"FINAL_REPORT",new Caller(user,Set.of(),"signed-user-token"))).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
    }
    @Test void unavailableDependenciesAreNotRetriedAndRedirectsAreNotFollowed() {
        code.set(503);assertThatThrownBy(()->remote.caller(jwt())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);assertThat(requests.get()).isEqualTo(1);
        code.set(302);assertThatThrownBy(()->remote.caller(jwt())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);assertThat(requests.get()).isEqualTo(2);assertThat(path.get()).isEqualTo("/api/v1/me");
    }
}
