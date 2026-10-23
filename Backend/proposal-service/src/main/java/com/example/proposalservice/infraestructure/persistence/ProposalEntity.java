package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "proposal",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_proposal_proposal_number",
                        columnNames = "proposal_number"
                )
        },
        indexes = {
                @Index(
                        name = "ix_proposal_owner_status",
                        columnList = "owner_user_id,status"
                ),
                @Index(
                        name = "ix_proposal_status_submitted_at",
                        columnList = "status,submitted_at"
                ),
                @Index(
                        name = "ix_proposal_research_area_status",
                        columnList = "research_area_id,status"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Size(max = 40)
    @Column(name = "proposal_number", length = 40, updatable = false)
    @Setter(AccessLevel.NONE)
    private String proposalNumber;

    @NotNull
    @Size(max = 500)
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "abstract_text", columnDefinition = "text")
    private String abstractText;

    @Column(name = "research_problem", columnDefinition = "text")
    private String researchProblem;

    @Column(name = "methodology", columnDefinition = "text")
    private String methodology;

    @Column(name = "expected_results", columnDefinition = "text")
    private String expectedResults;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "research_area_id",
            referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_proposal_research_area")
    )
    private ResearchAreaEntity researchArea;

    @Positive
    @Column(name = "estimated_duration_months")
    private Integer estimatedDurationMonths;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ProposalStatus status = ProposalStatus.DRAFT;

    // External Identity service reference, not a JPA relationship.
    @NotNull
    @Column(name = "owner_user_id", nullable = false)

    private UUID ownerUserId;

    @Column(name = "submitted_at", columnDefinition = "timestamptz")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "cancelled_at", columnDefinition = "timestamptz")
    private Instant cancelledAt;

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

    public ProposalEntity(
            String proposalNumber,
            String title,
            UUID ownerUserId,
            UUID actorUserId
    ) {
        this.proposalNumber = proposalNumber;
        this.title = title;
        this.ownerUserId = ownerUserId;
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