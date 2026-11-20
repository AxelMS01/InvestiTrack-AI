package com.example.proposalservice.api;

import com.example.proposalservice.application.ProposalApplicationService;
import com.example.proposalservice.api.ApiModels.ProjectReference;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/v1/proposals/{proposalId}")
public class InternalProposalController {
    private final ProposalApplicationService service;
    public InternalProposalController(ProposalApplicationService service){this.service=service;}
    @GetMapping("/project-creation-data")
    public Map<String,Object> snapshot(@PathVariable UUID proposalId,@RequestParam(required=false) UUID approvalId){return service.approved(proposalId,approvalId);}
    @PutMapping("/project-reference")
    public ResponseEntity<Void> reference(@PathVariable UUID proposalId,@Valid @RequestBody ProjectReference input){service.projectReference(proposalId,input);return ResponseEntity.noContent().build();}
    @GetMapping("/access-context")
    public Map<String,Object> context(@PathVariable UUID proposalId,@RequestParam UUID subjectId){return service.accessContext(proposalId,subjectId);}
}
