package com.example.proposalservice.infrastructure;

import com.example.proposalservice.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.example.proposalservice.api.ApiException.require;

@Component
public class IdentityClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String identity,identityToken,document;
    public IdentityClient(ObjectMapper json,@Value("${proposal.owners.identity.url}") String identity,
        @Value("${proposal.owners.identity.token}") String token,@Value("${proposal.owners.document.url}") String document) {
        this.json=json;this.identity=identity;this.identityToken=token;this.document=document;
    }
    public Caller resolve(Jwt jwt) {
        var profile=get(identity,"/api/v1/me",jwt.getTokenValue(),true);
        try {
            require(Set.of("ACTIVE","DEACTIVATED","PENDING").contains(Objects.toString(profile.get("status"),"")),503,"DEPENDENCY_UNAVAILABLE","Identity returned an invalid account state.");
            require("ACTIVE".equals(profile.get("status")),403,"ACCOUNT_INACTIVE","The account is not active.");
            UUID id=UUID.fromString(profile.get("id").toString());String signed=jwt.getClaimAsString("user_id");
            require(signed==null||signed.equals(id.toString()),403,"USER_MAPPING_MISMATCH","The signed user mapping does not match Identity.");
            var roles=new HashSet<String>();for(var role:(List<?>)profile.get("roles"))roles.add(role.toString());
            return new Caller(id,roles.contains("COORDINATOR"),roles.contains("ADMIN"),roles.contains("RESEARCHER"),roles.contains("AUDITOR"),roles.contains("EVALUATOR"));
        }catch(ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    public void active(UUID user,String role) {
        require(!identityToken.isBlank(),503,"DEPENDENCY_UNAVAILABLE","Identity workload credentials are required.");
        var profile=get(identity,"/internal/v1/users/"+user+"/status",identityToken,false);
        require(user.toString().equals(Objects.toString(profile.get("userId")))&&profile.get("active") instanceof Boolean&&profile.get("roles") instanceof List<?>,503,"DEPENDENCY_UNAVAILABLE","Identity returned an invalid account status.");
        require(Boolean.TRUE.equals(profile.get("active")),422,"INACTIVE_USER","The selected account must be active.");
        if(role!=null)require(((List<?>)profile.get("roles")).contains(role),422,"ROLE_REQUIRED","The selected account requires role "+role+".");
    }
    public void documents(UUID proposal,List<UUID> documents) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        require(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken,401,"UNAUTHENTICATED","A signed user token is required.");
        String token=((org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)auth).getToken().getTokenValue();
        for(UUID id:documents) {
            var metadata=get(document,"/api/v1/documents/"+id,token,true);
            var raw=metadata.get("context");
            require(raw instanceof Map<?,?>,503,"DEPENDENCY_UNAVAILABLE","Document metadata must include its owner context.");
            var context=(Map<?,?>)raw;
            require(id.toString().equals(Objects.toString(metadata.getOrDefault("id",metadata.get("documentId"))))
                &&proposal.toString().equals(Objects.toString(context.get("proposalId")))&&context.get("projectId")==null,422,"INVALID_DOCUMENT_ASSOCIATION","The document must belong to this proposal.");
            require("AVAILABLE".equals(metadata.getOrDefault("status",metadata.get("processingStatus"))),422,"DOCUMENT_UNAVAILABLE","Only scanned, available documents may be attached.");
        }
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> get(String base,String path,String token,boolean user) {
        if(base.isBlank()||token.isBlank())throw unavailable();
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(1)).header("Accept","application/json").header("Authorization","Bearer "+token);
            if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                var cid=attrs.getRequest().getAttribute("correlationId");if(cid!=null)builder.header("X-Correlation-ID",cid.toString());
                var trace=attrs.getRequest().getHeader("traceparent");if(trace!=null&&trace.matches("[a-f0-9-]{55}"))builder.header("traceparent",trace);
            }
            var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
            if(user&&(response.statusCode()==401||response.statusCode()==403))throw new ApiException(response.statusCode(),response.statusCode()==401?"UNAUTHENTICATED":"FORBIDDEN","The service denied access.");
            if(response.statusCode()==404)throw new ApiException(422,"REFERENCED_RESOURCE_NOT_FOUND","The selected resource does not exist or is not visible.");
            if(response.statusCode()!=200)throw unavailable();var result=json.readValue(response.body(),Map.class);if(result==null)throw unavailable();return result;
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
    public static ApiException unavailable(){return new ApiException(503,"DEPENDENCY_UNAVAILABLE","A required service is unavailable or returned invalid data.");}
}
