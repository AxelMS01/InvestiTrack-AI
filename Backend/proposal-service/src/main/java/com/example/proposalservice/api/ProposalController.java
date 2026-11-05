package com.example.proposalservice.api;

import com.example.proposalservice.application.ProposalApplicationService;
import com.example.proposalservice.api.ApiModels.*;
import com.example.proposalservice.infraestructure.persistence.ProposalStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/proposals")
public class ProposalController {
    private final ProposalApplicationService service;
    public ProposalController(ProposalApplicationService service) { this.service=service; }
    private Caller caller() { return Caller.current(); }
    static ResponseEntity<Map<String,Object>> ok(Map<String,Object> body) { return ResponseEntity.ok().eTag("\""+body.get("version")+"\"").body(body); }
    static ResponseEntity<Map<String,Object>> created(String path,Map<String,Object> body) { return ResponseEntity.created(URI.create(path+"/"+body.get("id"))).eTag("\""+body.get("version")+"\"").body(body); }
    private CollectionResponse<Map<String,Object>> page(List<Map<String,Object>> values,int limit,String cursor) { return CursorPages.page(values,limit,cursor,"id",Set.of("id")); }

    @GetMapping
    public CollectionResponse<Map<String,Object>> list(@RequestParam(required=false) String q,@RequestParam(required=false) List<ProposalStatus> status,
        @RequestParam(required=false) String researchArea,@RequestParam(required=false) UUID researcherId,@RequestParam(required=false) UUID evaluatorId,
        @RequestParam(required=false) Instant submittedFrom,@RequestParam(required=false) Instant submittedTo,
        @RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="-updatedAt") String sort) {
        return service.proposals(caller(),q,status,researchArea,researcherId,evaluatorId,submittedFrom,submittedTo,limit,cursor,sort);
    }
    @PostMapping
    public ResponseEntity<Map<String,Object>> create(@Valid @RequestBody ProposalInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) { return created("/api/v1/proposals",service.create(input,caller(),key)); }
    @GetMapping("/{proposalId}")
    public ResponseEntity<Map<String,Object>> get(@PathVariable UUID proposalId) { return ok(service.get(proposalId,caller())); }
    @PatchMapping("/{proposalId}")
    public ResponseEntity<Map<String,Object>> update(@PathVariable UUID proposalId,@Valid @RequestBody ProposalInput input,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.update(proposalId,input,caller(),etag)); }
    @DeleteMapping("/{proposalId}")
    public ResponseEntity<Void> delete(@PathVariable UUID proposalId,@RequestHeader(value="If-Match",required=false) String etag) { service.delete(proposalId,caller(),etag); return ResponseEntity.noContent().build(); }
    @GetMapping("/{proposalId}/history")
    public CollectionResponse<Map<String,Object>> history(@PathVariable UUID proposalId,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return page(service.history(proposalId,caller(),false),limit,cursor); }
    @PostMapping("/{proposalId}/transitions")
    public ResponseEntity<Map<String,Object>> transition(@PathVariable UUID proposalId,@Valid @RequestBody Transition input,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.transition(proposalId,input,caller(),etag)); }

    @GetMapping("/{proposalId}/researchers")
    public ResponseEntity<CollectionResponse<Map<String,Object>>> researchers(@PathVariable UUID proposalId,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {
        var c=caller(); var proposal=service.get(proposalId,c); return ResponseEntity.ok().eTag("\""+proposal.get("version")+"\"").body(page(service.listResearchers(proposalId,c),limit,cursor));
    }
    @PostMapping("/{proposalId}/researchers")
    public ResponseEntity<Map<String,Object>> addResearcher(@PathVariable UUID proposalId,@Valid @RequestBody ResearcherInput input,@RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key) { return created("/api/v1/proposals/"+proposalId+"/researchers",service.addResearcher(proposalId,input,caller(),etag,key)); }
    @PatchMapping("/{proposalId}/researchers/{userId}")
    public ResponseEntity<Map<String,Object>> updateResearcher(@PathVariable UUID proposalId,@PathVariable UUID userId,@Valid @RequestBody ResearcherInput input,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.saveResearcher(proposalId,userId,input,caller(),etag,false)); }
    @DeleteMapping("/{proposalId}/researchers/{userId}")
    public ResponseEntity<Void> removeResearcher(@PathVariable UUID proposalId,@PathVariable UUID userId,@RequestHeader(value="If-Match",required=false) String etag) { service.deleteResearcher(proposalId,userId,caller(),etag); return ResponseEntity.noContent().build(); }

    @GetMapping("/{proposalId}/evaluator-assignments")
    public CollectionResponse<Map<String,Object>> assignments(@PathVariable UUID proposalId,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return page(service.assignments(proposalId,caller()),limit,cursor); }
    @PostMapping("/{proposalId}/evaluator-assignments")
    public ResponseEntity<Map<String,Object>> assign(@PathVariable UUID proposalId,@Valid @RequestBody AssignmentInput input,@RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key) { return created("/api/v1/proposals/"+proposalId+"/evaluator-assignments",service.createAssignment(proposalId,input,caller(),etag,key)); }
    @GetMapping("/{proposalId}/evaluator-assignments/{assignmentId}")
    public ResponseEntity<Map<String,Object>> assignment(@PathVariable UUID proposalId,@PathVariable UUID assignmentId) { return ok(service.getAssignment(proposalId,assignmentId,caller())); }
    @PatchMapping("/{proposalId}/evaluator-assignments/{assignmentId}")
    public ResponseEntity<Map<String,Object>> updateAssignment(@PathVariable UUID proposalId,@PathVariable UUID assignmentId,@Valid @RequestBody AssignmentInput input,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.updateAssignment(proposalId,assignmentId,input,caller(),etag)); }
    @DeleteMapping("/{proposalId}/evaluator-assignments/{assignmentId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID proposalId,@PathVariable UUID assignmentId,@RequestHeader(value="If-Match",required=false) String etag) { service.revoke(proposalId,assignmentId,caller(),etag); return ResponseEntity.noContent().build(); }

    @GetMapping("/{proposalId}/evaluations")
    public CollectionResponse<Map<String,Object>> evaluations(@PathVariable UUID proposalId,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return page(service.evaluations(proposalId,caller()),limit,cursor); }
    @PostMapping("/{proposalId}/evaluations")
    public ResponseEntity<Map<String,Object>> createEvaluation(@PathVariable UUID proposalId,@Valid @RequestBody EvaluationInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) { return created("/api/v1/proposals/"+proposalId+"/evaluations",service.createEvaluation(proposalId,input,caller(),key)); }
    @GetMapping("/{proposalId}/evaluations/{evaluationId}")
    public ResponseEntity<Map<String,Object>> evaluation(@PathVariable UUID proposalId,@PathVariable UUID evaluationId) { return ok(service.getEvaluation(proposalId,evaluationId,caller())); }
    @PatchMapping("/{proposalId}/evaluations/{evaluationId}")
    public ResponseEntity<Map<String,Object>> updateEvaluation(@PathVariable UUID proposalId,@PathVariable UUID evaluationId,@Valid @RequestBody EvaluationInput input,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.updateEvaluation(proposalId,evaluationId,input,caller(),etag)); }
    @PostMapping("/{proposalId}/evaluations/{evaluationId}/submission")
    public ResponseEntity<Map<String,Object>> submit(@PathVariable UUID proposalId,@PathVariable UUID evaluationId,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.submit(proposalId,evaluationId,caller(),etag)); }
    @PostMapping("/{proposalId}/evaluations/{evaluationId}/release")
    public ResponseEntity<Map<String,Object>> release(@PathVariable UUID proposalId,@PathVariable UUID evaluationId,@RequestHeader(value="If-Match",required=false) String etag) { return ok(service.release(proposalId,evaluationId,caller(),etag)); }
    @GetMapping("/{proposalId}/evaluations/{evaluationId}/history")
    public CollectionResponse<Map<String,Object>> evaluationHistory(@PathVariable UUID proposalId,@PathVariable UUID evaluationId,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return page(service.evaluationHistory(proposalId,evaluationId,caller()),limit,cursor); }
}
