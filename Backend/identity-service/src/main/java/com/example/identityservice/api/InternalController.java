package com.example.identityservice.api;

import com.example.identityservice.api.ApiModels.*;
import com.example.identityservice.application.*;
import com.example.identityservice.infrastructure.IdentityRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.identityservice.api.ApiException.require;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final IdentityService identity;
    private final IdentityRepository repo;
    private final AuthorizationService authorization;
    private final Set<String> clients;
    public InternalController(IdentityService identity, IdentityRepository repo, AuthorizationService authorization,
        @Value("${identity.security.workload-clients:}") String clients) {
        this.identity=identity; this.repo=repo; this.authorization=authorization;
        this.clients=new HashSet<>(Arrays.asList(clients.split(",")));
    }
    private void subject(Jwt jwt, UUID id) {
        String scope=Objects.toString(jwt.getClaimAsString("scope"),"");
        if(Arrays.asList(scope.split(" ")).contains("identity:any-subject")) { workload(jwt); return; }
        require(identity.caller(jwt).id().equals(id),403,"SUBJECT_MISMATCH","subjectId must match the authenticated application user.");
    }
    private void workload(Jwt jwt) {
        String client=jwt.getClaimAsString("client_id");
        require(client!=null && !client.isBlank() && clients.contains(client) && jwt.getSubject().equals(client),
            403,"WORKLOAD_NOT_ALLOWED","An explicitly allowed workload identity is required.");
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @GetMapping("/users/{id}/status")
    public Map<String,Object> status(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        workload(jwt); var u=repo.user(id);
        return Map.of("userId",id,"active",u.active(),"roles",repo.roles(id),"identityVersion",u.version());
    }
    @PostMapping("/authorization/check")
    public Decision check(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Check in) {
        subject(jwt,in.subjectId()); return authorization.check(in);
    }
    @PostMapping("/authorization/filter")
    public FilterResult filter(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Filter in) {
        subject(jwt,in.subjectId()); return authorization.filter(in);
    }
}
