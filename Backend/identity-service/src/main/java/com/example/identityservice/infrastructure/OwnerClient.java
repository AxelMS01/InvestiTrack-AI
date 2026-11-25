package com.example.identityservice.infrastructure;

import com.example.identityservice.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class OwnerClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Map<String,String> urls;
    private final Map<String,String> tokens;
    public OwnerClient(ObjectMapper json,
        @Value("${identity.owners.project.url:}") String project,
        @Value("${identity.owners.proposal.url:}") String proposal,
        @Value("${identity.owners.document.url:}") String document,
        @Value("${identity.owners.project.token:}") String projectToken,
        @Value("${identity.owners.proposal.token:}") String proposalToken,
        @Value("${identity.owners.document.token:}") String documentToken) {
        this.json=json; urls=Map.of("project",project,"proposal",proposal,"document",document);
        tokens=Map.of("project",projectToken,"proposal",proposalToken,"document",documentToken);
    }
    public JsonNode context(String owner, String path, long deadlineNanos) {
        String token=tokens.get(owner);
        if(token==null || token.isBlank()) throw unavailable();
        return get(owner,path,token,deadlineNanos,false);
    }
    public JsonNode visible(String owner, String path, String bearer) {
        return get(owner,path,bearer,System.nanoTime()+Duration.ofMillis(2500).toNanos(),true);
    }
    private JsonNode get(String owner, String path, String token, long deadline, boolean delegated) {
        String base=urls.get(owner); long remaining=deadline-System.nanoTime();
        if(base==null || base.isBlank() || remaining<=0) throw unavailable();
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path))
                .timeout(Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(1).toNanos())))
                .header("Authorization","Bearer "+token).header("Accept","application/json");
            var attrs=RequestContextHolder.getRequestAttributes();
            if(attrs instanceof ServletRequestAttributes s) {
                var correlation=s.getRequest().getAttribute("correlationId");
                if(correlation!=null) request.header("X-Correlation-ID",correlation.toString());
                String trace=s.getRequest().getHeader("traceparent");
                if(trace!=null && trace.matches("[a-f0-9-]{55}")) request.header("traceparent",trace);
            }
            var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404) throw new ApiException(404,"RESOURCE_NOT_FOUND","The resource is absent or not visible.");
            if(delegated && response.statusCode()==403) throw new ApiException(403,"FORBIDDEN","The domain service denied access.");
            if(response.statusCode()!=200) throw unavailable();
            var result=json.readTree(response.body());
            if(result==null || !result.isObject()) throw unavailable();
            return result;
        } catch(ApiException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(Exception e) { throw unavailable(); }
    }
    public static ApiException unavailable() { return new ApiException(503,"AUTHORIZATION_DEPENDENCY_UNAVAILABLE","A required domain service is unavailable or returned an invalid access context."); }
    public static String query(String value) { return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8); }
}
