package com.example.identityservice.api;

import com.example.identityservice.api.ApiModels.*;
import com.example.identityservice.application.*;
import com.example.identityservice.infrastructure.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
import static com.example.identityservice.api.ApiException.require;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService service;
    private final IdentityRepository repo;
    private final OwnerClient owners;
    public IdentityController(IdentityService service,IdentityRepository repo,OwnerClient owners) { this.service=service; this.repo=repo; this.owners=owners; }
    private ResponseEntity<UserView> response(UserView u) { return ResponseEntity.ok().eTag("\""+u.version()+"\"").body(u); }
    @GetMapping("/me")
    public ResponseEntity<UserView> me(@AuthenticationPrincipal Jwt jwt) { return response(service.me(jwt)); }
    @PatchMapping("/me")
    public ResponseEntity<UserView> patchMe(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PatchUser input,@RequestHeader(value="If-Match",required=false) String match) {
        var caller=service.caller(jwt); return response(service.patch(caller,caller.id(),input,match,true));
    }
    @GetMapping("/me/permissions")
    public Map<String,Object> permissions(@AuthenticationPrincipal Jwt jwt) {
        var u=service.caller(jwt); return Map.of("userId",u.id(),"roles",repo.roles(u.id()),"permissions",repo.capabilities(u.id()),"policyVersion",repo.policyVersion(),"identityVersion",u.version());
    }
    @GetMapping({"/me/projects","/me/proposals","/me/evaluation-assignments"})
    public Object visible(@AuthenticationPrincipal Jwt jwt,jakarta.servlet.http.HttpServletRequest request,
        @RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {
        var u=service.caller(jwt); require(limit>=1&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        String collection=request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);
        if(collection.equals("evaluation-assignments")) require(repo.roles(u.id()).contains("EVALUATOR"),403,"FORBIDDEN","Evaluator role is required.");
        String path="/api/v1/"+collection+"?limit="+limit+(cursor==null?"":"&cursor="+OwnerClient.query(cursor));
        return owners.visible(collection.equals("projects")?"project":"proposal",path,jwt.getTokenValue());
    }
    @GetMapping("/users")
    public Page<?> users(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,
        @RequestParam(required=false) List<String> status,@RequestParam(required=false) String role,
        @RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {
        return service.search(service.caller(jwt),q,status,role,limit,cursor);
    }
    @PostMapping("/users")
    public ResponseEntity<UserView> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateUser input,
        @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var u=service.provision(service.caller(jwt),input,key);
        return ResponseEntity.created(URI.create("/api/v1/users/"+u.id())).eTag("\""+u.version()+"\"").body(u);
    }
    @GetMapping("/users/{id}")
    public ResponseEntity<?> user(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        var result=service.lookupUser(service.caller(jwt),id);
        if(result instanceof UserView u) return response(u);
        return ResponseEntity.ok(result);
    }
    @PatchMapping("/users/{id}")
    public ResponseEntity<UserView> patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody PatchUser input,
        @RequestHeader(value="If-Match",required=false) String match) {
        return response(service.patch(service.caller(jwt),id,input,match,false));
    }
    @PostMapping("/users/{id}/deactivation")
    public ResponseEntity<UserView> deactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Reason reason,
        @RequestHeader(value="If-Match",required=false) String match) {
        return response(service.state(service.caller(jwt),id,false,reason.reason(),match));
    }
    @DeleteMapping("/users/{id}/deactivation")
    public ResponseEntity<UserView> reactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
        @RequestHeader(value="If-Match",required=false) String match) {
        return response(service.state(service.caller(jwt),id,true,"Administrator reactivation",match));
    }
    @GetMapping("/users/{id}/roles")
    public ResponseEntity<?> assignments(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        service.admin(service.caller(jwt)); var u=repo.user(id);
        var rows=repo.db().sql("select r.code,ur.assigned_at,ur.assigned_by,ur.reason from user_role ur join app_role r on r.id=ur.role_id where ur.user_id=:id order by r.code")
            .param("id",id).query((r,n)-> { var m=new LinkedHashMap<String,Object>(); m.put("roleCode",r.getString(1)); m.put("assignedAt",r.getTimestamp(2).toInstant()); m.put("assignedBy",r.getObject(3,UUID.class)); m.put("reason",r.getString(4)); return m; }).list();
        return ResponseEntity.ok().eTag("\""+u.version()+"\"").body(Map.of("roles",repo.roles(id),"assignments",rows));
    }
    @PutMapping("/users/{id}/roles")
    public ResponseEntity<UserView> roles(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Roles roles,
        @RequestHeader(value="If-Match",required=false) String match) {
        return response(service.roles(service.caller(jwt),id,roles.roles(),roles.reason(),match,null,null));
    }
    @PostMapping("/users/{id}/roles/{role}")
    public ResponseEntity<UserView> add(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable String role,
        @Valid @RequestBody Reason reason,@RequestHeader(value="If-Match",required=false) String match) {
        return response(service.roles(service.caller(jwt),id,null,reason.reason(),match,role,null));
    }
    @DeleteMapping("/users/{id}/roles/{role}")
    public ResponseEntity<UserView> remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable String role,
        @RequestHeader(value="If-Match",required=false) String match) {
        return response(service.roles(service.caller(jwt),id,null,"Administrator role removal",match,null,role));
    }
    @GetMapping("/roles")
    public Page<RoleView> catalog(@AuthenticationPrincipal Jwt jwt) {
        service.admin(service.caller(jwt)); return new Page<>(repo.catalog(),new PageInfo(100,null,false));
    }
    @GetMapping("/roles/{code}")
    public RoleView role(@AuthenticationPrincipal Jwt jwt,@PathVariable String code) {
        service.admin(service.caller(jwt)); return repo.catalog().stream().filter(r->r.code().equals(code)).findFirst()
            .orElseThrow(()->new ApiException(404,"ROLE_NOT_FOUND","Role does not exist."));
    }
}
