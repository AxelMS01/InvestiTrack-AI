package com.example.documentservice;

import com.example.documentservice.api.ApiException;
import com.example.documentservice.infrastructure.ObjectStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ObjectStorageTests {
    @Test void presigningUsesPublicEndpointAndBoundedExpirationWithoutCallingStorage(){
        var storage=new ObjectStorage("http://internal:9000","https://download.example","test-access","test-secret","documents","us-east-1");
        var upload=URI.create(storage.uploadUrl("uploads/test"));assertThat(upload.getHost()).isEqualTo("download.example");assertThat(upload.getRawQuery()).contains("X-Amz-Expires=900","X-Amz-Signature=");
        var download=URI.create(storage.downloadUrl("documents/test","Research é.pdf"));assertThat(download.getRawQuery()).contains("X-Amz-Expires=60","response-content-disposition=","response-cache-control=");
    }
    @Test void storageClientSignsServerRequestsAndBoundsReads() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var authorization=new AtomicReference<String>();
        server.createContext("/documents/",e->{authorization.set(e.getRequestHeaders().getFirst("Authorization"));byte[] bytes="too much content".getBytes();e.getResponseHeaders().set("Content-Type","application/octet-stream");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try {String url="http://127.0.0.1:"+server.getAddress().getPort();var storage=new ObjectStorage(url,url,"test-access","test-secret","documents","us-east-1");
            assertThatThrownBy(()->storage.read("object",3)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(413);
            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 ");
        }finally{server.stop(0);}
    }
}
