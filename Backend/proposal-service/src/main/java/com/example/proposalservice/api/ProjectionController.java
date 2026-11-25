package com.example.proposalservice.api;

import com.example.proposalservice.application.ProposalProjections;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/v1")
public class ProjectionController {
    private final ProposalProjections service;
    public ProjectionController(ProposalProjections service){this.service=service;}
    @GetMapping("/projection-state/{aggregate}/{id}")
    public Map<String,Object> state(@PathVariable String aggregate,@PathVariable UUID id){return service.state(aggregate,id);}
    @GetMapping("/projection-snapshots")
    public Map<String,Object> snapshot(@RequestParam String projection,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit){return service.snapshot(projection,cursor,limit);}
}
