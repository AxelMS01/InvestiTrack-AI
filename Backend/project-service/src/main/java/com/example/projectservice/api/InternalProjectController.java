package com.example.projectservice.api;

import com.example.projectservice.api.ApiModels.*;
import com.example.projectservice.application.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/v1")
public class InternalProjectController {
    private final ProjectService service;
    private final ProjectionService projections;
    public InternalProjectController(ProjectService service,ProjectionService projections){this.service=service;this.projections=projections;}
    @GetMapping("/projects/{project}/access-context")
    public Map<String,Object> context(@PathVariable UUID project,@RequestParam UUID subjectId){return service.accessContext(project,subjectId);}
    @PostMapping("/projects/access-contexts")
    public Map<String,Object> contexts(@Valid @RequestBody AccessBatch input){return service.contexts(input);}
    @GetMapping("/activities/{item}/access-context")
    public Map<String,Object> activity(@PathVariable UUID item,@RequestParam UUID subjectId){return service.childContext("activity",item,subjectId);}
    @GetMapping("/milestones/{item}/access-context")
    public Map<String,Object> milestone(@PathVariable UUID item,@RequestParam UUID subjectId){return service.childContext("milestone",item,subjectId);}
    @PostMapping("/projects/{project}/association-validation")
    public Map<String,Object> association(@PathVariable UUID project,@Valid @RequestBody Association input){return service.association(project,input);}
    @GetMapping("/projection-state/{aggregate}/{id}")
    public Map<String,Object> projection(@PathVariable String aggregate,@PathVariable UUID id){return projections.state(aggregate,id);}
    @GetMapping("/projection-snapshots")
    public Map<String,Object> snapshots(@RequestParam String projection,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit){return projections.snapshot(projection,cursor,limit);}
}
