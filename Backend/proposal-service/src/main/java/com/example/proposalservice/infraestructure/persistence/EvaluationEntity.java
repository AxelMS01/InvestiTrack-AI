package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "evaluation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_evaluation_assignment_id", columnNames = {"assignment_id"})
    },
    indexes = {
        @Index(name = "ix_evaluation_proposal_id_status", columnList = "proposal_id, status"),
        @Index(name = "ix_evaluation_evaluator_user_id_status", columnList = "evaluator_user_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationEntity extends AuditedEntity {

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evaluation_assignment"))
    private EvaluatorAssignmentEntity assignment;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evaluation_proposal"))
    private ProposalEntity proposal;

    @NotNull
    @Column(name = "evaluator_user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID evaluatorUserId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EvaluationStatus status;

    @Column(name = "observations", columnDefinition = "text")
    private String observations;

    @Column(name = "recommendations", columnDefinition = "text")
    private String recommendations;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 30)
    private EvaluationDecision decision;

    @Digits(integer = 6, fraction = 2)
    @Column(name = "total_score", precision = 8, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "submitted_at", columnDefinition = "timestamptz")
    private Instant submittedAt;

    @Column(name = "released_at", columnDefinition = "timestamptz")
    private Instant releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    public EvaluationEntity(EvaluatorAssignmentEntity assignment, ProposalEntity proposal, UUID evaluatorUserId, EvaluationStatus status, UUID actorUserId) {
        super(actorUserId);
        this.assignment = assignment;
        this.proposal = proposal;
        this.evaluatorUserId = evaluatorUserId;
        this.status = status;
    }
}
