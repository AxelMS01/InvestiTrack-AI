package com.example.documentservice.api;

import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.application.*;
import com.example.documentservice.infrastructure.RemoteServices;
import jakarta.validation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.example.documentservice.api.ApiException.require;

@RestController
@RequestMapping("/internal/v1/documents/{id}")
public class InternalDocumentController {
    private final Documents documents;private final Processing processing;private final RemoteServices remote;private final ObjectMapper json;private final Validator validator;
    public InternalDocumentController(Documents documents,Processing processing,RemoteServices remote,ObjectMapper json,Validator validator){this.documents=documents;this.processing=processing;this.remote=remote;this.json=json;this.validator=validator;}
    @GetMapping("/access-context") public Map<String,Object> context(@PathVariable UUID id){return documents.context(id);}
    @PostMapping("/scan-results") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void scan(@PathVariable UUID id,@RequestBody String body,@RequestHeader(value="X-Result-Signature",required=false) String signature){processing.signature(body,signature);Scan input=json.readValue(body,Scan.class);require(validator.validate(input).isEmpty(),400,"INVALID_REQUEST","Invalid scan callback fields.");processing.scan(id,input);}
    @PostMapping("/extractions") @ResponseStatus(HttpStatus.NO_CONTENT) public void extraction(@PathVariable UUID id,@Valid @RequestBody Extraction input){processing.extraction(id,input);}
    @GetMapping("/indexing-content") public Map<String,Object> content(@PathVariable UUID id,@RequestParam int documentVersion,@RequestParam(required=false) String cursor){return processing.content(id,documentVersion,cursor);}
    @PostMapping("/indexing-status") @ResponseStatus(HttpStatus.NO_CONTENT) public void index(@PathVariable UUID id,@Valid @RequestBody Indexing input){processing.index(id,input);}
    @PostMapping("/source-validation") public Map<String,Object> source(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Source input){return processing.source(remote.delegated(jwt),id,input);}
    @PostMapping("/reference-validation") public Map<String,Object> reference(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Reference input){return documents.reference(remote.delegated(jwt),id,input);}
    @PutMapping("/holds/{reference}") @ResponseStatus(HttpStatus.NO_CONTENT) public void hold(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID reference,@Valid @RequestBody Reference input){documents.hold(remote.delegated(jwt),id,reference,input,jwt.getClaimAsString("client_id"));}
    @DeleteMapping("/holds/{reference}") @ResponseStatus(HttpStatus.NO_CONTENT) public void release(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID reference){documents.release(id,reference,jwt.getClaimAsString("client_id"));}
    @GetMapping("/processing-jobs/{job}/content") public Map<String,Object> workerContent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID job){return processing.workerContent(id,job,jwt.getClaimAsString("client_id"));}
}
