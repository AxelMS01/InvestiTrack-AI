package com.example.projectservice.infrastructure;

import com.example.projectservice.api.*;
import com.example.projectservice.api.ApiModels.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.example.projectservice.api.ApiException.*;

@Component
public class RemoteServices {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Map<String,String> urls,tokens;
    private final String exchangeUri,exchangeClient,exchangeSecret,documentAudience;
    public RemoteServices(ObjectMapper json,
        @Value("${project.owners.identity.url}") String identity,@Value("${project.owners.proposal.url}") String proposal,
        @Value("${project.owners.document.url}") String document,@Value("${project.owners.identity.token}") String identityToken,
        @Value("${project.owners.proposal.token}") String proposalToken,@Value("${project.owners.document.token}") String documentToken,
        @Value("${project.token-exchange.uri}") String exchangeUri,@Value("${project.token-exchange.client-id}") String exchangeClient,
        @Value("${project.token-exchange.client-secret}") String exchangeSecret,@Value("${project.token-exchange.document-audience}") String audience) {
        this.json=json;urls=Map.of("identity",identity,"proposal",proposal,"document",document);
        tokens=Map.of("identity",identityToken,"proposal",proposalToken,"document",documentToken);
        this.exchangeUri=exchangeUri;this.exchangeClient=exchangeClient;this.exchangeSecret=exchangeSecret;this.documentAudience=audience;
    }
    public Caller caller(Jwt jwt) {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(attrs!=null&&attrs.getAttribute("projectCaller",RequestAttributes.SCOPE_REQUEST) instanceof Caller c)return c;
        var profile=call("identity","GET","/api/v1/me",null,jwt.getTokenValue(),true);
        try {
            require(Set.of("ACTIVE","DEACTIVATED","PENDING").contains(Objects.toString(profile.get("status"),"")),503,"DEPENDENCY_UNAVAILABLE","Identity returned an invalid profile status.");
            if(!"ACTIVE".equals(profile.get("status")))throw new ApiException(403,"ACCOUNT_INACTIVE","The account is not active.");
            var id=UUID.fromString(profile.get("id").toString());
            String signed=jwt.getClaimAsString("user_id");
            require(signed==null||signed.equals(id.toString()),403,"USER_MAPPING_MISMATCH","The signed application user mapping is invalid.");
            var roles=new HashSet<String>();for(Object r:(List<?>)profile.get("roles"))roles.add(r.toString());
            var caller=new Caller(id,Set.copyOf(roles),jwt.getTokenValue());
            if(attrs!=null)attrs.setAttribute("projectCaller",caller,RequestAttributes.SCOPE_REQUEST);
            return caller;
        } catch(ApiException e){throw e;} catch(RuntimeException e){throw unavailable();}
    }
    public void activeUser(UUID user) {
        var result=workload("identity","GET","/internal/v1/users/"+user+"/status",null);
        require(user.toString().equals(Objects.toString(result.get("userId")))&&result.get("active") instanceof Boolean,503,"DEPENDENCY_UNAVAILABLE","Identity returned an invalid account status.");
        require(Boolean.TRUE.equals(result.get("active")),422,"INVALID_MEMBER","The selected user must be an active application account.");
    }
    public Map<String,Object> approved(UUID proposal,UUID approval) {
        return workload("proposal","GET","/internal/v1/proposals/"+proposal+"/project-creation-data"+(approval==null?"":"?approvalId="+approval),null);
    }
    public void recordProject(UUID proposal,UUID approval,UUID project) {
        workload("proposal","PUT","/internal/v1/proposals/"+proposal+"/project-reference",Map.of("approvalId",approval,"projectId",project));
    }
    public Map<String,Object> validateDocument(UUID project,UUID document,int version,String purpose,Caller caller) {
        var result=call("document","POST","/internal/v1/documents/"+document+"/reference-validation",
            Map.of("projectId",project,"documentVersion",version,"purpose",purpose),delegated(caller),true);
        require(Boolean.TRUE.equals(result.get("valid")),422,"DOCUMENT_REFERENCE_INVALID","The document is not available or authorized for this association.");
        try {
            require(((Number)result.get("documentVersion")).intValue()==version&&result.get("sha256").toString().matches("[a-fA-F0-9]{64}"),503,"DEPENDENCY_UNAVAILABLE","Document validation returned an invalid version or checksum.");
        }catch(NullPointerException|ClassCastException e){throw unavailable();}
        return result;
    }
    public void publicationDocument(UUID project,UUID document,Caller caller) {
        var result=call("document","GET","/api/v1/documents/"+document,null,caller.token(),true);
        require(document.toString().equals(Objects.toString(result.get("id"),Objects.toString(result.get("documentId")))),503,"DEPENDENCY_UNAVAILABLE","Document metadata did not match the requested document.");
        Object version=result.getOrDefault("documentVersion",result.get("currentVersion"));
        require(version instanceof Number,503,"DEPENDENCY_UNAVAILABLE","Document metadata must include its current documentVersion.");
        validateDocument(project,document,((Number)version).intValue(),"EVIDENCE",caller);
    }
    public void hold(UUID project,UUID document,int version,String reference,Caller caller) {
        call("document","PUT","/internal/v1/documents/"+document+"/holds/"+reference,
            Map.of("projectId",project,"documentVersion",version,"purpose","FINAL_REPORT"),delegated(caller),true);
    }
    public void release(UUID document,UUID reference) {
        workload("document","DELETE","/internal/v1/documents/"+document+"/holds/"+reference,null);
    }
    private String delegated(Caller caller) {
        if(exchangeUri.isBlank()||exchangeSecret.isBlank())throw unavailable();
        try {
            String body="grant_type="+encode("urn:ietf:params:oauth:grant-type:token-exchange")+"&subject_token="+encode(caller.token())
                +"&subject_token_type="+encode("urn:ietf:params:oauth:token-type:access_token")+"&requested_token_type="+encode("urn:ietf:params:oauth:token-type:access_token")+"&audience="+encode(documentAudience);
            var request=HttpRequest.newBuilder(URI.create(exchangeUri)).timeout(Duration.ofSeconds(1))
                .header("Content-Type","application/x-www-form-urlencoded").header("Authorization","Basic "+Base64.getEncoder().encodeToString((encode(exchangeClient)+":"+encode(exchangeSecret)).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw unavailable();
            var tree=json.readTree(response.body());
            if(tree==null||!tree.path("access_token").isString()||tree.path("access_token").asString().isBlank())throw unavailable();
            return tree.path("access_token").asString();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
    private Map<String,Object> workload(String owner,String method,String path,Object body) {
        var token=tokens.get(owner);if(token==null||token.isBlank())throw unavailable();
        return call(owner,method,path,body,token,false);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> call(String owner,String method,String path,Object body,String token,boolean delegated) {
        String base=urls.get(owner);if(base==null||base.isBlank())throw unavailable();
        try {
            var uri=URI.create(base+path);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null)throw unavailable();
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).header("Authorization","Bearer "+token).header("Accept","application/json");
            if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                var cid=attrs.getRequest().getAttribute("correlationId");if(cid!=null)request.header("X-Correlation-ID",cid.toString());
            }
            request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(delegated&&(response.statusCode()==401||response.statusCode()==403))throw new ApiException(response.statusCode(),response.statusCode()==401?"UNAUTHENTICATED":"FORBIDDEN","The service denied access to this operation.");
            if(response.statusCode()==404)throw new ApiException(404,"RESOURCE_NOT_FOUND","The required resource is absent or not visible.");
            if(response.statusCode()==409)throw new ApiException(409,"DEPENDENCY_CONFLICT","The dependency rejected the requested state or reference.");
            if(response.statusCode()==204)return Map.of();
            if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();
            var result=json.readValue(response.body(),Map.class);if(result==null)throw unavailable();return result;
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
    private static String encode(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
}
