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
    name = "proposal_status_history",
    indexes = {
        @Index(name = "ix_proposal_status_history_proposal_id_changed_at", columnList = "proposal_id, changed_at desc")
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalStatusHistoryEntity extends UuidEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_proposal_status_history_proposal"))
    private ProposalEntity proposal;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ProposalStatus fromStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private ProposalStatus toStatus;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @NotNull
    @Column(name = "changed_at", nullable = false, columnDefinition = "timestamptz")
    private Instant changedAt;

    @NotNull
    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;

    @Size(max = 100)
    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    public ProposalStatusHistoryEntity(ProposalEntity proposal, ProposalStatus toStatus, Instant changedAt, UUID changedBy) {
        this.proposal = proposal;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
    }
}
