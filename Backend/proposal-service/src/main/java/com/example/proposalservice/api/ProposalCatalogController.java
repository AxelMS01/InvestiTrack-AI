package com.example.proposalservice.api;

import com.example.proposalservice.application.ProposalApplicationService;
import com.example.proposalservice.api.ApiModels.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ProposalCatalogController {
    private final ProposalApplicationService service;
    public ProposalCatalogController(ProposalApplicationService service) { this.service=service; }
    @GetMapping("/evaluation-criteria")
    public CollectionResponse<Map<String,Object>> criteria(@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return CursorPages.page(service.criteria(Caller.current()),limit,cursor,"id",Set.of("id")); }
    @GetMapping("/evaluation-criteria/{criterionCode}")
    public Map<String,Object> criterion(@PathVariable String criterionCode) { return service.getCriterion(criterionCode,Caller.current()); }
    @GetMapping("/reference/research-areas")
    public CollectionResponse<Map<String,Object>> areas(@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) { return CursorPages.page(service.researchAreas(),limit,cursor,"id",Set.of("id")); }
}
