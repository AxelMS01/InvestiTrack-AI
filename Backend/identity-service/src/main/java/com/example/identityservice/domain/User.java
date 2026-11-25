package com.example.identityservice.domain;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String identitySubject, String institutionalId, String email,
                   String displayName, String givenName, String familyName, Status status,
                   String locale, Instant lastAuthenticatedAt, Instant createdAt, UUID createdBy,
                   Instant updatedAt, UUID updatedBy, long version) {
    public enum Status { ACTIVE, DEACTIVATED, PENDING }
    public boolean active() { return status == Status.ACTIVE; }
}
