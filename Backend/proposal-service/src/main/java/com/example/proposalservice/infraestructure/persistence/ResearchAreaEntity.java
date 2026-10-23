package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "research_area",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_research_area_code",
                        columnNames = "code"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResearchAreaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Size(max = 50)
    @Column(name = "code", length = 50)
    private String code;

    @NotNull
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            columnDefinition = "timestamptz"
    )
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @NotNull
    @Column(name = "created_by", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID createdBy;

    @Column(
            name = "updated_at",
            nullable = false,
            columnDefinition = "timestamptz"
    )
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @NotNull
    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long version;

    public ResearchAreaEntity(
            String code,
            String name,
            String description,
            UUID actorUserId
    ) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdBy = actorUserId;
        this.updatedBy = actorUserId;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}