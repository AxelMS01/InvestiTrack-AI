package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "proposal_decision",
    indexes = {
        @Index(name = "ix_proposal_decision_proposal_id_decided_at", columnList = "proposal_id, decided_at desc")
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalDecisionEntity extends UuidEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_proposal_decision_proposal"))
    private ProposalEntity proposal;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private ProposalDecision decision;

    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    @NotNull
    @Column(name = "decided_at", nullable = false, columnDefinition = "timestamptz")
    private Instant decidedAt;

    @NotNull
    @Column(name = "decided_by", nullable = false)
    private UUID decidedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "supersedes_decision_id", nullable = true, foreignKey = @ForeignKey(name = "fk_proposal_decision_supersedes_decision"))
    private ProposalDecisionEntity supersedesDecision;

    public ProposalDecisionEntity(ProposalEntity proposal, ProposalDecision decision, Instant decidedAt, UUID decidedBy) {
        this.proposal = proposal;
        this.decision = decision;
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy;
    }
}
