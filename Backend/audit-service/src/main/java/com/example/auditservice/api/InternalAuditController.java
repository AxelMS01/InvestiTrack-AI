package com.example.auditservice.api;

import com.example.auditservice.api.ApiModels.AuditInput;
import com.example.auditservice.application.Ingestion;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1")
public class InternalAuditController {
    private final Ingestion ingestion;
    public InternalAuditController(Ingestion ingestion){this.ingestion=ingestion;}
    @PostMapping("/audit-events") public ResponseEntity<?> append(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AuditInput input){var result=ingestion.append(input,jwt.getClaimAsString("client_id"));return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(Map.of("eventId",result.eventId(),"accepted",true));}
}
