package com.example.identityservice.api;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpenApiController {
    @GetMapping(value="/v3/api-docs",produces=MediaType.APPLICATION_JSON_VALUE)
    public Resource contract() { return new ClassPathResource("openapi.json"); }
}
