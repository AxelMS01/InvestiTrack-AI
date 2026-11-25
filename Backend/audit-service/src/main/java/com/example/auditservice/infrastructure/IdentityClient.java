package com.example.auditservice.infrastructure;

import com.example.auditservice.api.*;
import com.example.auditservice.api.ApiModels.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.example.auditservice.api.ApiException.*;

@Component
public class IdentityClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;private final String url,token;
    public IdentityClient(ObjectMapper json,@Value("${audit.identity.url}") String url,@Value("${audit.identity.token}") String token){this.json=json;this.url=url;this.token=token;}
    public Caller caller(Jwt jwt) {
        var profile=get("/api/v1/me",jwt.getTokenValue());
        require(Set.of("ACTIVE","DEACTIVATED","PENDING").contains(Objects.toString(profile.get("status"),"")),503,"DEPENDENCY_UNAVAILABLE","Invalid account status.");
        require("ACTIVE".equals(profile.get("status")),403,"ACCOUNT_INACTIVE","An active account is required.");
        try {UUID id=UUID.fromString(profile.get("id").toString());String mapped=jwt.getClaimAsString("user_id");require(mapped==null||mapped.equals(id.toString()),403,"SUBJECT_MISMATCH","The signed user mapping is invalid.");return new Caller(id,roles(profile.get("roles")));}
        catch(ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    public Caller active(UUID id) {
        if(token.isBlank())throw unavailable();var profile=get("/internal/v1/users/"+id+"/status",token);
        require(id.toString().equals(Objects.toString(profile.get("userId")))&&profile.get("active") instanceof Boolean,503,"DEPENDENCY_UNAVAILABLE","Invalid account status.");
        require(Boolean.TRUE.equals(profile.get("active")),403,"ACCOUNT_INACTIVE","An active account is required.");return new Caller(id,roles(profile.get("roles")));
    }
    private Set<String> roles(Object value){if(!(value instanceof List<?> list)||list.stream().anyMatch(r->!(r instanceof String)))throw unavailable();var result=new HashSet<String>();((List<?>)value).forEach(r->result.add(r.toString()));return Set.copyOf(result);}
    @SuppressWarnings("unchecked")
    private Map<String,Object> get(String path,String credential) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(1)).header("Authorization","Bearer "+credential).header("Accept","application/json").GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200)throw unavailable();byte[] bytes=body.readNBytes(65537);if(bytes.length>65536)throw unavailable();
                var result=json.readValue(bytes,Map.class);if(result==null)throw unavailable();return result;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
}
