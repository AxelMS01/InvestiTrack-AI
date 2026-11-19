package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "proposal_objective",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_proposal_objective_proposal_id_display_order", columnNames = {"proposal_id", "display_order"})
    }
)
@org.hibernate.annotations.Check(constraints = "(display_order >= 0)")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalObjectiveEntity extends AuditedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_proposal_objective_proposal"))
    private ProposalEntity proposal;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "objective_type", nullable = false, length = 20)
    private ObjectiveType objectiveType;

    @NotNull
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @NotNull
    @PositiveOrZero
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    public ProposalObjectiveEntity(ProposalEntity proposal, ObjectiveType objectiveType, String description, Integer displayOrder, UUID actorUserId) {
        super(actorUserId);
        this.proposal = proposal;
        this.objectiveType = objectiveType;
        this.description = description;
        this.displayOrder = displayOrder;
    }
}
