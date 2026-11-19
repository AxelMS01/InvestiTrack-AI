package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "evaluator_assignment"
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluatorAssignmentEntity extends AuditedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evaluator_assignment_proposal"))
    private ProposalEntity proposal;

    @NotNull
    @Column(name = "evaluator_user_id", nullable = false)
    private UUID evaluatorUserId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EvaluatorAssignmentStatus status;

    @NotNull
    @Column(name = "assigned_at", nullable = false, columnDefinition = "timestamptz")
    private Instant assignedAt;

    @NotNull
    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "instructions", columnDefinition = "text")
    private String instructions;

    @Column(name = "completed_at", columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revocation_reason", columnDefinition = "text")
    private String revocationReason;

    public EvaluatorAssignmentEntity(ProposalEntity proposal, UUID evaluatorUserId, EvaluatorAssignmentStatus status, Instant assignedAt, UUID assignedBy, UUID actorUserId) {
        super(actorUserId);
        this.proposal = proposal;
        this.evaluatorUserId = evaluatorUserId;
        this.status = status;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }
}
