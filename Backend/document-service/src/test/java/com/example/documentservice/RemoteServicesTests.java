package com.example.documentservice;

import com.example.documentservice.api.*;
import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.infrastructure.RemoteServices;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class RemoteServicesTests {
    HttpServer server;ObjectMapper json=new ObjectMapper();RemoteServices remote;
    AtomicReference<Map<String,Object>> response=new AtomicReference<>();AtomicReference<String> authorization=new AtomicReference<>();AtomicReference<Map> body=new AtomicReference<>();int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] input=exchange.getRequestBody().readAllBytes();if(input.length>0)body.set(json.readValue(input,Map.class));byte[] bytes=json.writeValueAsBytes(response.get());exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();remote=new RemoteServices(json,url,"identity-workload",url,"project-workload");
    }
    @AfterEach void close(){server.stop(0);}
    Jwt jwt(String user){return Jwt.withTokenValue("signed-user-token").header("alg","RS256").subject("external-oidc-subject").claim("user_id",user).build();}
    @Test void mapsExternalSubjectThroughIdentityAndChecksActiveState(){
        UUID id=UUID.randomUUID();response.set(Map.of("id",id,"status","ACTIVE","roles",List.of("RESEARCHER")));
        assertThat(remote.caller(jwt(id.toString())).id()).isEqualTo(id);assertThat(authorization.get()).isEqualTo("Bearer signed-user-token");
        assertThatThrownBy(()->remote.caller(jwt(UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
        response.set(Map.of("id",id,"roles",List.of()));assertThatThrownBy(()->remote.caller(jwt(id.toString()))).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
    }
    @Test void authorizationUsesWorkloadCredentialsAndNeverAcceptsMalformedOrExpandedScope(){
        UUID id=UUID.randomUUID();Caller caller=new Caller(UUID.randomUUID(),Set.of("RESEARCHER"));
        response.set(Map.of("allowed",true,"policyVersion","1"));assertThat(remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id)).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer identity-workload");assertThat(body.get().get("subjectId")).isEqualTo(caller.id().toString());
        response.set(Map.of("allowed","true","policyVersion","1"));assertThatThrownBy(()->remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id)).isInstanceOf(ApiException.class);
        response.set(Map.of("allowedResourceIds",List.of(UUID.randomUUID()),"policyVersion","1","evaluatedAt",Instant.now().toString()));assertThatThrownBy(()->remote.filter(caller,List.of(Map.of("type","DOCUMENT","id",id)))).isInstanceOf(ApiException.class);
        status=503;assertThatThrownBy(()->remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id)).isInstanceOf(ApiException.class);
    }
    @Test void contextRequiresOneOwnerAndValidatesProjectChildren(){
        Caller caller=new Caller(UUID.randomUUID(),Set.of());assertThatThrownBy(()->remote.context(caller,new Context(null,null,null,null))).isInstanceOf(ApiException.class);
        response.set(Map.of("allowed",true,"policyVersion","1","valid",false));
        assertThatThrownBy(()->remote.context(caller,new Context(null,UUID.randomUUID(),UUID.randomUUID(),null))).isInstanceOf(ApiException.class).hasMessageContaining("does not belong");
        assertThat(authorization.get()).isEqualTo("Bearer project-workload");
    }
    @Test void delegatedIdentityRequiresSignedApplicationMappingAndAnActiveAccount(){
        UUID id=UUID.randomUUID();response.set(Map.of("userId",id,"active",true,"roles",List.of("RESEARCHER")));
        var token=Jwt.withTokenValue("delegated").header("alg","RS256").subject("oidc-sub").claim("client_id","project-service").claim("user_id",id.toString()).build();
        assertThat(remote.delegated(token).id()).isEqualTo(id);
        response.set(Map.of("userId",id,"active",false,"roles",List.of()));assertThatThrownBy(()->remote.delegated(token)).isInstanceOf(ApiException.class);
        var workload=Jwt.withTokenValue("workload").header("alg","RS256").subject("project-service").claim("client_id","project-service").claim("user_id",id.toString()).build();assertThatThrownBy(()->remote.delegated(workload)).isInstanceOf(ApiException.class);
    }
}
