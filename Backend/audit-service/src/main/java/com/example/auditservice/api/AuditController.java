package com.example.auditservice.api;

import com.example.auditservice.api.ApiModels.*;
import com.example.auditservice.application.*;
import com.example.auditservice.infrastructure.IdentityClient;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AuditController {
    private final Queries queries;private final Exports exports;private final IdentityClient identity;
    public AuditController(Queries queries,Exports exports,IdentityClient identity){this.queries=queries;this.exports=exports;this.identity=identity;}
    @GetMapping("/audit-events") public Map<String,Object> search(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> filters){return queries.search(identity.caller(jwt),filters);}
    @GetMapping("/audit-events/{id}") public Map<String,Object> event(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return queries.get(identity.caller(jwt),id);}
    @PostMapping("/audit-export-jobs") public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ExportInput input,@RequestHeader(value="Idempotency-Key",required=false) String key){var response=exports.create(identity.caller(jwt),input,key);return ResponseEntity.accepted().location(URI.create("/api/v1/audit-export-jobs/"+response.get("id"))).body(response);}
    @GetMapping("/audit-export-jobs/{id}") public Map<String,Object> job(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return exports.get(identity.caller(jwt),id);}
    @PostMapping("/audit-export-jobs/{id}/download-authorization") public Map<String,Object> authorize(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return exports.authorize(identity.caller(jwt),id);}
    @GetMapping("/audit-export-jobs/{id}/content") public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam String token){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"audit-"+id+".csv\"").cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(exports.download(identity.caller(jwt),id,token));}
}
