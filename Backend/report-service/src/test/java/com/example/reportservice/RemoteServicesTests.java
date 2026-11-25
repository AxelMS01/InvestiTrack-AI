package com.example.reportservice;

import com.example.reportservice.api.*;
import com.example.reportservice.api.ApiModels.Caller;
import com.example.reportservice.infrastructure.RemoteServices;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class RemoteServicesTests {
    HttpServer server;
    RemoteServices remote;
    ObjectMapper json=new ObjectMapper();
    UUID user=UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        remote=new RemoteServices(json,base,"identity-workload",base,"project-workload",base,"proposal-workload");
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void userIdentityComesFromLiveProfileAndForwardedSignedToken() {
        server.createContext("/api/v1/me",e->{assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer signed-user-token");byte[] body=json.writeValueAsBytes(Map.of("id",user,"status","ACTIVE","roles",List.of("RESEARCHER")));e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});
        var token=Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("institutional-subject").build();
        assertThat(remote.caller(token).id()).isEqualTo(user);
        var mismatch=Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("institutional-subject").claim("user_id",UUID.randomUUID().toString()).build();
        assertThatThrownBy(()->remote.caller(mismatch)).isInstanceOf(ApiException.class);
    }
    @Test void batchesAuthorizationAndRejectsExtraResourceIds() {
        AtomicInteger calls=new AtomicInteger();
        server.createContext("/internal/v1/authorization/filter",e->{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer identity-workload");
            var body=json.readTree(e.getRequestBody());assertThat(body.path("resources").size()).isLessThanOrEqualTo(100);assertThat(body.path("subjectId").asString()).isEqualTo(user.toString());
            var ids=new ArrayList<String>();body.path("resources").forEach(r->ids.add(r.path("id").asString()));calls.incrementAndGet();
            byte[] response=json.writeValueAsBytes(Map.of("allowedResourceIds",ids));e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}
        });
        var ids=new ArrayList<UUID>();for(int i=0;i<205;i++)ids.add(UUID.randomUUID());
        assertThat(remote.allowed(new Caller(user,Set.of("RESEARCHER")),"PROJECT",ids)).containsExactlyInAnyOrderElementsOf(ids);assertThat(calls.get()).isEqualTo(3);
        server.removeContext("/internal/v1/authorization/filter");respond("/internal/v1/authorization/filter",200,json.writeValueAsString(Map.of("allowedResourceIds",List.of(UUID.randomUUID()))));
        assertThatThrownBy(()->remote.allowed(new Caller(user,Set.of()),"PROJECT",ids)).isInstanceOf(ApiException.class);
    }
    @Test void dependencyFailuresAndMalformedResponsesNeverGrantAccess() {
        respond("/internal/v1/authorization/filter",503,"{}");var caller=new Caller(user,Set.of());
        assertThatThrownBy(()->remote.allowed(caller,"PROJECT",List.of(UUID.randomUUID()))).isInstanceOf(ApiException.class);
        server.removeContext("/internal/v1/authorization/filter");respond("/internal/v1/authorization/filter",200,"{}");
        assertThatThrownBy(()->remote.allowed(caller,"PROJECT",List.of(UUID.randomUUID()))).isInstanceOf(ApiException.class);
    }
    void respond(String path,int status,String data){server.createContext(path,e->{byte[] bytes=data.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});}
}
