package com.example.reportservice.api;

import jakarta.validation.constraints.*;
import java.util.*;

public final class ApiModels {
    private ApiModels() {}
    public record Caller(UUID id, Set<String> roles) {
        public boolean has(String... allowed) { return roles.contains("ADMIN") || Arrays.stream(allowed).anyMatch(roles::contains); }
    }
    public record CreateReport(@NotBlank String reportCode,@NotBlank String format,@NotNull Map<String,Object> parameters) {}
    public record Rebuild(@NotBlank String projection,@NotNull Map<String,Object> scope,@NotBlank @Size(max=500) String reason) {}
}
