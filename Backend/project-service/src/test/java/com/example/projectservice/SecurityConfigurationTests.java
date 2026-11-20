package com.example.projectservice;

import com.example.projectservice.api.SecurityConfiguration;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

class SecurityConfigurationTests {
    HttpServer server;
    RSAKey key;
    String issuer;
    JwtDecoder decoder;
    @BeforeEach void setup() throws Exception {
        key=new RSAKeyGenerator(2048).keyID("test-key").generate();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        issuer="http://127.0.0.1:"+server.getAddress().getPort()+"/issuer";
        respond("/issuer/.well-known/openid-configuration","{\"issuer\":\""+issuer+"\",\"jwks_uri\":\""+issuer+"/jwks\",\"authorization_endpoint\":\""+issuer+"/authorize\",\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}");
        respond("/issuer/jwks",new JWKSet(key.toPublicJWK()).toString());
        server.start();
        decoder=new SecurityConfiguration().jwtDecoder(issuer,issuer+"/jwks","research-platform-api","project-service");
    }
    void respond(String path,String content) {
        server.createContext(path,exchange->{
            var body=content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        });
    }
    @AfterEach void stop() { if(server!=null) server.stop(0); }
    String token(String issuer,String audience,Instant expires,RSAKey signer) throws Exception {
        var claims=new JWTClaimsSet.Builder().subject("institutional|user").issuer(issuer).audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expires)).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),claims);
        jwt.sign(new RSASSASigner(signer)); return jwt.serialize();
    }
    @Test void supportsInternalJwksWithPublicIssuer() throws Exception {
        String publicIssuer="http://localhost:8180/realms/investitrack";
        var internalDecoder=new SecurityConfiguration().jwtDecoder(publicIssuer,issuer+"/jwks","research-platform-api","project-service");
        assertThat(internalDecoder.decode(token(publicIssuer,"research-platform-api",Instant.now().plusSeconds(300),key)).getIssuer().toString()).isEqualTo(publicIssuer);
        var wrongIssuer=token(issuer,"research-platform-api",Instant.now().plusSeconds(300),key);
        assertThatThrownBy(()->internalDecoder.decode(wrongIssuer)).isInstanceOf(JwtException.class);
    }
    @Test void validatesSignatureIssuerLifetimeAndBothConfiguredAudiences() throws Exception {
        assertThat(decoder.decode(token(issuer,"research-platform-api",Instant.now().plusSeconds(300),key)).getSubject()).isEqualTo("institutional|user");
        assertThat(decoder.decode(token(issuer,"project-service",Instant.now().plusSeconds(300),key)).getAudience()).contains("project-service");
        var wrongAudience=token(issuer,"unrelated-service",Instant.now().plusSeconds(300),key);
        assertThatThrownBy(()->decoder.decode(wrongAudience)).isInstanceOf(JwtException.class);
        var wrongIssuer=token("https://untrusted.example","research-platform-api",Instant.now().plusSeconds(300),key);
        assertThatThrownBy(()->decoder.decode(wrongIssuer)).isInstanceOf(JwtException.class);
        var expired=token(issuer,"research-platform-api",Instant.now().minusSeconds(300),key);
        assertThatThrownBy(()->decoder.decode(expired)).isInstanceOf(JwtException.class);
        var wrongSignature=token(issuer,"research-platform-api",Instant.now().plusSeconds(300),new RSAKeyGenerator(2048).keyID("test-key").generate());
        assertThatThrownBy(()->decoder.decode(wrongSignature)).isInstanceOf(JwtException.class);
        var incomplete=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            new JWTClaimsSet.Builder().issuer(issuer).subject("institutional|user").audience("research-platform-api").build());
        incomplete.sign(new RSASSASigner(key));
        assertThatThrownBy(()->decoder.decode(incomplete.serialize())).isInstanceOf(JwtException.class);
        var missingAudience=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            new JWTClaimsSet.Builder().issuer(issuer).subject("institutional|user").expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        missingAudience.sign(new RSASSASigner(key));
        assertThatThrownBy(()->decoder.decode(missingAudience.serialize())).isInstanceOf(JwtException.class);
    }
}
