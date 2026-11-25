package com.example.projectservice.api;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpenApiController {
    @GetMapping(value="/v3/api-docs",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> contract(){return ResponseEntity.ok(new ClassPathResource("openapi.json"));}
}
