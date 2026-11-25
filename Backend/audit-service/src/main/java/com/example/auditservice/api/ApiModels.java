package com.example.auditservice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiModels {
    private ApiModels() { }
    public enum ActorType { USER, SERVICE, SYSTEM }
    public enum Outcome { SUCCESS, FAILURE, DENIED }
    public record Actor(@NotNull ActorType type,@NotBlank @Size(max=255) String id) { }
    public record Resource(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,49}") String type,UUID id) { }
    public record AuditInput(@NotNull UUID eventId,@NotNull Instant occurredAt,@NotNull @Valid Actor actor,
        @NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_.:-]{0,99}") String action,@NotNull @Valid Resource resource,
        @NotNull Outcome outcome,@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String correlationId,
        @Size(max=100) Map<String,Object> metadata) { }
    public record ExportInput(@NotNull @Size(max=12) Map<String,String> filters) { }
    public record Caller(UUID id,Set<String> roles) { }
}
