package com.example.auditservice;

import com.example.auditservice.api.ApiException;
import com.example.auditservice.infrastructure.IdentityClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class IdentityClientTests {
    HttpServer server;ObjectMapper json=new ObjectMapper();IdentityClient client;
    AtomicReference<Map<String,Object>> profile=new AtomicReference<>();AtomicReference<String> auth=new AtomicReference<>();int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{auth.set(e.getRequestHeaders().getFirst("Authorization"));byte[] bytes=json.writeValueAsBytes(profile.get());e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();client=new IdentityClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"audit-workload");
    }
    @AfterEach void close(){server.stop(0);}
    Jwt jwt(UUID id){return Jwt.withTokenValue("signed-user").header("alg","RS256").subject("external-idp-subject").claim("user_id",id.toString()).build();}
    @Test void currentAccountAndRolesComeFromIdentityAndSignedMappingMustMatch(){
        UUID id=UUID.randomUUID();profile.set(Map.of("id",id,"status","ACTIVE","roles",List.of("AUDITOR")));assertThat(client.caller(jwt(id)).id()).isEqualTo(id);assertThat(auth.get()).isEqualTo("Bearer signed-user");
        assertThatThrownBy(()->client.caller(jwt(UUID.randomUUID()))).isInstanceOf(ApiException.class);
        profile.set(Map.of("id",id,"status","DEACTIVATED","roles",List.of("AUDITOR")));assertThatThrownBy(()->client.caller(jwt(id))).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
    }
    @Test void workloadLookupIsBoundedAndRejectsInvalidOrUnavailableResponses(){
        UUID id=UUID.randomUUID();profile.set(Map.of("userId",id,"active",true,"roles",List.of("ADMIN")));assertThat(client.active(id).roles()).contains("ADMIN");assertThat(auth.get()).isEqualTo("Bearer audit-workload");
        profile.set(Map.of("userId",UUID.randomUUID(),"active",true,"roles",List.of()));assertThatThrownBy(()->client.active(id)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        profile.set(Map.of("userId",id,"active",true,"roles","AUDITOR"));assertThatThrownBy(()->client.active(id)).isInstanceOf(ApiException.class);
        status=503;assertThatThrownBy(()->client.active(id)).isInstanceOf(ApiException.class);
        status=200;profile.set(Map.of("id",id,"status","ACTIVE","roles",List.of("AUDITOR"),"extra","x".repeat(70000)));assertThatThrownBy(()->client.caller(jwt(id))).isInstanceOf(ApiException.class);
    }
}
