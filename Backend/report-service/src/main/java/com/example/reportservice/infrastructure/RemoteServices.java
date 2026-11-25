package com.example.reportservice.infrastructure;

import com.example.reportservice.api.*;
import com.example.reportservice.api.ApiModels.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.example.reportservice.api.ApiException.*;

@Component
public class RemoteServices {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Map<String,String> urls,tokens;
    public RemoteServices(ObjectMapper json,
        @Value("${report.identity.url}") String identity,@Value("${report.identity.token}") String identityToken,
        @Value("${report.project.url}") String project,@Value("${report.project.token}") String projectToken,
        @Value("${report.proposal.url}") String proposal,@Value("${report.proposal.token}") String proposalToken) {
        this.json=json;urls=Map.of("identity",identity,"project",project,"proposal",proposal);
        tokens=Map.of("identity",identityToken,"project",projectToken,"proposal",proposalToken);
    }
    public Caller caller(Jwt jwt) {
        var profile=call("identity","GET","/api/v1/me",null,jwt.getTokenValue());
        try {
            require(Set.of("ACTIVE","DEACTIVATED","PENDING").contains(Objects.toString(profile.get("status"),"")),503,"DEPENDENCY_UNAVAILABLE","Identity returned an invalid account status.");
            if(!"ACTIVE".equals(profile.get("status")))throw new ApiException(403,"ACCOUNT_INACTIVE","An active account is required.");
            UUID id=UUID.fromString(profile.get("id").toString());
            require(jwt.getClaimAsString("user_id")==null||id.toString().equals(jwt.getClaimAsString("user_id")),403,"USER_MAPPING_MISMATCH","Invalid application identity mapping.");
            return new Caller(id,roles(profile.get("roles")));
        }catch(ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    public Caller active(UUID id) {
        var result=workload("identity","GET","/internal/v1/users/"+id+"/status",null);
        require(id.toString().equals(Objects.toString(result.get("userId")))&&result.get("active") instanceof Boolean,503,"DEPENDENCY_UNAVAILABLE","Invalid identity response.");
        require(Boolean.TRUE.equals(result.get("active")),403,"ACCOUNT_INACTIVE","An active account is required.");
        try{return new Caller(id,roles(result.get("roles")));}catch(RuntimeException e){throw unavailable();}
    }
    private Set<String> roles(Object raw) {
        if(!(raw instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof String)))throw unavailable();
        var roles=new HashSet<String>();list.forEach(v->roles.add((String)v));return Set.copyOf(roles);
    }
    public Set<UUID> allowed(Caller caller,String type,Collection<UUID> ids) {
        var unique=new ArrayList<>(new LinkedHashSet<>(ids));var allowed=new HashSet<UUID>();
        for(int i=0;i<unique.size();i+=100) {
            var batch=unique.subList(i,Math.min(i+100,unique.size()));
            var resources=batch.stream().map(id->Map.of("type",type,"id",id)).toList();
            var result=workload("identity","POST","/internal/v1/authorization/filter",Map.of("subjectId",caller.id(),"action",type.equals("PROJECT")?"PROJECT_READ":"PROPOSAL_READ","resources",resources));
            try {
                if(!(result.get("allowedResourceIds") instanceof List<?> list))throw unavailable();
                for(var id:list){UUID value=UUID.fromString(id.toString());if(!batch.contains(value))throw unavailable();allowed.add(value);}
            }catch(RuntimeException e){throw unavailable();}
        }
        return allowed;
    }
    public Map<String,Object> state(String owner,String type,UUID id) {
        var result=workload(owner,"GET","/internal/v1/projection-state/"+type+"/"+id,null);
        require(type.equals(result.get("aggregateType"))&&id.toString().equals(Objects.toString(result.get("aggregateId"))),503,"INVALID_PROJECTION","The owner returned a different aggregate.");
        return result;
    }
    public Map<String,Object> snapshot(String owner,String projection,String cursor) {
        return workload(owner,"GET","/internal/v1/projection-snapshots?projection="+projection+"&limit=100"+(cursor==null?"":"&cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8)),null);
    }
    private Map<String,Object> workload(String owner,String method,String path,Object body) {
        var token=tokens.get(owner);if(token.isBlank())throw unavailable();return call(owner,method,path,body,token);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> call(String owner,String method,String path,Object body,String token) {
        try {
            var request=HttpRequest.newBuilder(URI.create(urls.get(owner)+path)).timeout(Duration.ofSeconds(2))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==409)throw new ApiException(409,"SNAPSHOT_EXPIRED","The source snapshot expired; restart the rebuild.");
            if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();
            var result=json.readValue(response.body(),Map.class);if(result==null)throw unavailable();return result;
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
}
