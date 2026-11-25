package com.example.documentservice.infrastructure;

import com.example.documentservice.api.ApiModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.example.documentservice.api.ApiException.*;

@Component
public class RemoteServices {
    private final ObjectMapper json;
    private final Map<String,String> urls,tokens;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    public RemoteServices(ObjectMapper json,@Value("${document.identity.url}") String identity,@Value("${document.identity.token}") String identityToken,
        @Value("${document.project.url}") String project,@Value("${document.project.token}") String projectToken) {
        this.json=json;urls=Map.of("identity",identity,"project",project);tokens=Map.of("identity",identityToken,"project",projectToken);
    }
    public Caller caller(Jwt jwt) {
        var profile=call("identity","GET","/api/v1/me",null,jwt.getTokenValue());
        try {
            require(Set.of("ACTIVE","DEACTIVATED","PENDING").contains(Objects.toString(profile.get("status"),"")),503,"DEPENDENCY_UNAVAILABLE","Invalid profile status.");
            require("ACTIVE".equals(profile.get("status")),403,"ACCOUNT_INACTIVE","An active account is required.");
            var id=UUID.fromString(profile.get("id").toString());String signed=jwt.getClaimAsString("user_id");
            require(signed==null||signed.equals(id.toString()),403,"SUBJECT_MISMATCH","The signed user mapping does not match.");
            var roles=new HashSet<String>();for(Object r:(List<?>)profile.get("roles"))roles.add(r.toString());return new Caller(id,Set.copyOf(roles));
        }catch(com.example.documentservice.api.ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    public Caller delegated(Jwt jwt) {
        require(jwt.getClaimAsString("user_id")!=null&&!Objects.equals(jwt.getSubject(),jwt.getClaimAsString("client_id")),403,"DELEGATED_USER_REQUIRED","A signed delegated application user is required.");
        UUID id;try{id=UUID.fromString(jwt.getClaimAsString("user_id"));}catch(IllegalArgumentException e){throw new com.example.documentservice.api.ApiException(403,"SUBJECT_MISMATCH","Invalid signed user mapping.");}
        return active(id);
    }
    public Caller active(UUID id) {
        var result=workload("identity","GET","/internal/v1/users/"+id+"/status",null);
        require(id.toString().equals(Objects.toString(result.get("userId")))&&result.get("active") instanceof Boolean,503,"DEPENDENCY_UNAVAILABLE","Invalid account status.");
        require(Boolean.TRUE.equals(result.get("active")),403,"ACCOUNT_INACTIVE","An active account is required.");
        try {var roles=new HashSet<String>();for(Object r:(List<?>)result.get("roles"))roles.add(r.toString());return new Caller(id,roles);}catch(RuntimeException e){throw unavailable();}
    }
    public boolean allowed(Caller caller,String action,String type,UUID id) {
        var result=workload("identity","POST","/internal/v1/authorization/check",Map.of("subjectId",caller.id(),"action",action,"resource",Map.of("type",type,"id",id)));
        require(result.get("allowed") instanceof Boolean&&result.get("policyVersion") instanceof String,503,"DEPENDENCY_UNAVAILABLE","Invalid authorization result.");
        return Boolean.TRUE.equals(result.get("allowed"));
    }
    public Set<UUID> filter(Caller caller,List<Map<String,Object>> resources) {
        if(resources.isEmpty())return Set.of();
        require(resources.size()<=100&&resources.stream().map(r->r.get("id")).distinct().count()==resources.size(),400,"INVALID_BATCH","Authorization batches must contain up to 100 distinct resources.");
        var response=workload("identity","POST","/internal/v1/authorization/filter",Map.of("subjectId",caller.id(),"action","DOCUMENT_READ","resources",resources));
        try {
            require(response.get("policyVersion") instanceof String&&response.get("evaluatedAt") instanceof String,503,"DEPENDENCY_UNAVAILABLE","Invalid authorization filter result.");
            var requested=new HashSet<UUID>();resources.forEach(r->requested.add((UUID)r.get("id")));
            var allowed=new HashSet<UUID>();for(Object id:(List<?>)response.get("allowedResourceIds"))allowed.add(UUID.fromString(id.toString()));
            require(requested.containsAll(allowed),503,"DEPENDENCY_UNAVAILABLE","Authorization expanded the requested scope.");return allowed;
        }catch(com.example.documentservice.api.ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    public void context(Caller caller,Context c) {
        require((c.projectId()==null)!=(c.proposalId()==null),422,"INVALID_CONTEXT","Exactly one project or proposal is required.");
        require(c.projectId()!=null||(c.activityId()==null&&c.milestoneId()==null),422,"INVALID_CONTEXT","Activities and milestones require a project.");
        String type=c.projectId()==null?"PROPOSAL":"PROJECT";UUID id=c.projectId()==null?c.proposalId():c.projectId();
        require(allowed(caller,"DOCUMENT_UPLOAD",type,id),404,"RESOURCE_NOT_FOUND","The context is absent or not writable.");
        if(c.projectId()!=null) {
            var body=new LinkedHashMap<String,Object>();body.put("purpose",c.activityId()!=null||c.milestoneId()!=null?"EVIDENCE":"FINAL_REPORT");
            if(c.activityId()!=null)body.put("activityId",c.activityId());if(c.milestoneId()!=null)body.put("milestoneId",c.milestoneId());
            var result=workload("project","POST","/internal/v1/projects/"+c.projectId()+"/association-validation",body);
            require(result.get("valid") instanceof Boolean,503,"DEPENDENCY_UNAVAILABLE","Invalid association result.");
            require(Boolean.TRUE.equals(result.get("valid")),422,"INVALID_CONTEXT","The activity or milestone does not belong to this writable project.");
        }
    }
    private Map<String,Object> workload(String owner,String method,String path,Object body) {
        String token=tokens.get(owner);if(token==null||token.isBlank())throw unavailable();return call(owner,method,path,body,token);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> call(String owner,String method,String path,Object body,String token) {
        try {
            var request=HttpRequest.newBuilder(URI.create(urls.get(owner)+path)).timeout(Duration.ofSeconds(1)).header("Authorization","Bearer "+token)
                .header("X-Correlation-ID",DomainEvents.correlation()).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>299)throw unavailable();
            return json.readValue(response.body(),Map.class);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
}
