package com.example.reportservice.api;

import com.example.reportservice.api.ApiModels.Rebuild;
import com.example.reportservice.application.Rebuilds;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/internal/v1/projections/rebuilds")
public class InternalReportingController {
    private final Rebuilds rebuilds;
    public InternalReportingController(Rebuilds rebuilds){this.rebuilds=rebuilds;}
    @PostMapping public ResponseEntity<Map<String,Object>> create(@Valid @RequestBody Rebuild input){var job=rebuilds.create(input);return ResponseEntity.accepted().location(URI.create("/internal/v1/projections/rebuilds/"+job.get("jobId"))).body(job);}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){return rebuilds.get(id);}
}
