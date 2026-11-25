package com.example.documentservice.api;

import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.application.Documents;
import com.example.documentservice.infrastructure.RemoteServices;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private final Documents documents;
    private final RemoteServices remote;
    public DocumentController(Documents documents,RemoteServices remote){this.documents=documents;this.remote=remote;}
    @PostMapping("/document-uploads")
    public ResponseEntity<?> initialize(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Upload input,@RequestHeader(value="Idempotency-Key",required=false) String key){var result=documents.initialize(remote.caller(jwt),null,input,key,null);return ResponseEntity.created(URI.create("/api/v1/document-uploads/"+result.get("uploadId"))).body(result);}
    @GetMapping("/document-uploads/{id}") public Map<String,Object> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return documents.upload(remote.caller(jwt),id);}
    @DeleteMapping("/document-uploads/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){documents.cancel(remote.caller(jwt),id);}
    @PostMapping("/document-uploads/{id}/completion") public ResponseEntity<?> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return ResponseEntity.accepted().location(URI.create("/api/v1/document-uploads/"+id)).body(documents.complete(remote.caller(jwt),id));}
    @GetMapping("/documents") public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> filters){return documents.list(remote.caller(jwt),filters);}
    @GetMapping("/documents/{id}") public ResponseEntity<?> metadata(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return response(documents.metadata(remote.caller(jwt),id));}
    @PatchMapping("/documents/{id}") public ResponseEntity<?> patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Patch input,@RequestHeader(value="If-Match",required=false) String match){return response(documents.patch(remote.caller(jwt),id,input,match));}
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String match){documents.delete(remote.caller(jwt),id,match);}
    @PostMapping("/documents/{id}/restoration") public ResponseEntity<?> restore(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String match){return response(documents.restore(remote.caller(jwt),id,match));}
    @GetMapping("/documents/{id}/versions") public Map<String,Object> versions(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return documents.versions(remote.caller(jwt),id);}
    @GetMapping("/documents/{id}/versions/{number}") public Map<String,Object> version(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable int number){return documents.versionMetadata(remote.caller(jwt),id,number);}
    @PostMapping("/documents/{id}/versions") public ResponseEntity<?> newVersion(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Upload input,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestHeader(value="If-Match",required=false) String match){var result=documents.initialize(remote.caller(jwt),id,input,key,match);return ResponseEntity.created(URI.create("/api/v1/document-uploads/"+result.get("uploadId"))).body(result);}
    @PostMapping("/documents/{id}/download-authorization") public Map<String,Object> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return documents.download(remote.caller(jwt),id,null);}
    @PostMapping("/documents/{id}/versions/{number}/download-authorization") public Map<String,Object> downloadVersion(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable int number){return documents.download(remote.caller(jwt),id,number);}
    @GetMapping("/documents/{id}/preview") public Map<String,Object> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){documents.metadata(remote.caller(jwt),id);return Map.of("supported",false);}
    @GetMapping("/documents/{id}/processing-status") public Map<String,Object> processing(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return documents.processing(remote.caller(jwt),id);}
    @GetMapping("/documents/{id}/associations") public Map<String,Object> associations(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return documents.associations(remote.caller(jwt),id);}
    @PostMapping("/documents/{id}/associations") public ResponseEntity<?> associate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Association input,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestHeader(value="If-Match",required=false) String match){var result=documents.associate(remote.caller(jwt),id,input,match,key);return ResponseEntity.created(URI.create("/api/v1/documents/"+id+"/associations/"+result.get("id"))).body(result);}
    @DeleteMapping("/documents/{id}/associations/{association}") @ResponseStatus(HttpStatus.NO_CONTENT) public void dissociate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID association,@RequestHeader(value="If-Match",required=false) String match){documents.dissociate(remote.caller(jwt),id,association,match);}
    @GetMapping("/reference/document-types") public Map<String,Object> types(){return Map.of("items",List.of("RESEARCH_PROTOCOL","EVIDENCE","FINAL_REPORT","PUBLICATION","OTHER"),"formats",com.example.documentservice.domain.FileRules.TYPES);}
    private ResponseEntity<?> response(Map<String,Object> body){return ResponseEntity.ok().eTag(body.get("version").toString()).cacheControl(CacheControl.noStore()).body(body);}
}
