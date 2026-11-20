package com.example.identityservice.api;

import com.example.identityservice.domain.User.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiModels {
    private ApiModels() {}
    public record CreateUser(@NotBlank @Size(max=255) String identitySubject,
        @Size(max=100) String institutionalId, @NotBlank @Email @Size(max=320) String email,
        @NotBlank @Size(max=250) String displayName, @Size(max=150) String givenName,
        @Size(max=150) String familyName, @Size(max=20) String locale, Status status,
        @Size(max=100) List<@NotBlank String> roles) {}
    public record PatchUser(@Size(min=1,max=250) String displayName,
        @Size(max=150) String givenName, @Size(max=150) String familyName,
        @Email @Size(min=1,max=320) String email, @Size(max=100) String institutionalId,
        @Size(max=20) String locale) {}
    public record Reason(@NotBlank @Size(max=2000) String reason) {}
    public record Roles(@NotNull @Size(max=100) List<@NotBlank String> roles,
                        @NotBlank @Size(max=2000) String reason) {}
    public record Resource(@NotBlank String type, @NotNull UUID id, UUID projectId) {}
    public record Check(@NotNull UUID subjectId, @NotBlank @Size(max=100) String action,
                        @NotNull @Valid Resource resource) {}
    public record Filter(@NotNull UUID subjectId, @NotBlank @Size(max=100) String action,
                         @NotNull @Size(max=100) List<@NotNull @Valid Resource> resources) {}
    public record Decision(boolean allowed, String policyVersion, String reasonCode,
                           long identityVersion, Long accessVersion) {}
    public record FilterResult(List<UUID> allowedResourceIds, String policyVersion, Instant evaluatedAt) {}
    public record UserView(UUID id, String institutionalId, String displayName, String email,
        String givenName, String familyName, String locale, Status status,
        List<String> roles, List<String> capabilities, long version) {}
    public record LookupUser(UUID id, String institutionalId, String displayName) {}
    public record RoleView(String code, String name, String description, boolean assignable,
                           List<String> capabilities) {}
    public record Page<T>(List<T> items, PageInfo page) {}
    public record PageInfo(int limit, String nextCursor, boolean hasMore) {}
}
