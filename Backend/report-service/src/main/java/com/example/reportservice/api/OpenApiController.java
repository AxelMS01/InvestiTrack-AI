package com.example.reportservice.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpenApiController {
    @GetMapping(value="/v3/api-docs",produces=MediaType.APPLICATION_JSON_VALUE)
    public ClassPathResource schema(){return new ClassPathResource("openapi.json");}
}
