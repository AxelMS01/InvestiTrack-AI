package com.example.identityservice.infrastructure;

import com.example.identityservice.api.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OwnerClientTests {
    HttpServer server;
    OwnerClient client;
    AtomicReference<String> authorization=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok",r->{
            authorization.set(r.getRequestHeaders().getFirst("Authorization"));
            var data="{\"items\":[],\"page\":{\"hasMore\":false}}".getBytes(StandardCharsets.UTF_8);
            r.getResponseHeaders().set("Content-Type","application/json");
            r.sendResponseHeaders(200,data.length); try(var out=r.getResponseBody()) { out.write(data); }
        });
        server.createContext("/redirect",r->{r.getResponseHeaders().set("Location","/ok");r.sendResponseHeaders(302,-1);r.close();});
        server.createContext("/missing",r->{r.sendResponseHeaders(404,-1);r.close();});
        server.createContext("/unavailable",r->{r.sendResponseHeaders(503,-1);r.close();});
        server.createContext("/malformed",r->{var data="[]".getBytes(StandardCharsets.UTF_8);r.sendResponseHeaders(200,data.length);try(var out=r.getResponseBody()){out.write(data);}});
        server.start(); String url="http://127.0.0.1:"+server.getAddress().getPort();
        client=new OwnerClient(JsonMapper.builder().build(),url,url,url,"project-workload","proposal-workload","document-workload");
    }
    @AfterEach void stop() { if(server!=null) server.stop(0); }
    long deadline() { return System.nanoTime()+Duration.ofSeconds(2).toNanos(); }
    @Test void internalReadsUseWorkloadTokensAndVisibleCollectionsForwardTheSignedUserToken() {
        assertThat(client.context("project","/ok",deadline()).isObject()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer project-workload");
        client.visible("proposal","/ok","signed-user-token");
        assertThat(authorization.get()).isEqualTo("Bearer signed-user-token");
    }
    @Test void dependencyFailuresMalformedResponsesRedirectsAndExpiredBudgetsFailClosed() {
        for(String path:new String[]{"/redirect","/unavailable","/malformed"})
            assertThatThrownBy(()->client.context("project",path,deadline())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503));
        assertThatThrownBy(()->client.context("project","/ok",System.nanoTime()-1)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503));
        assertThatThrownBy(()->client.context("project","/missing",deadline())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
    }
}
