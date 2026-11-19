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
@Table(name = "proposal_researcher")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalResearcherEntity {

    @EmbeddedId
    @jakarta.validation.Valid
    @NotNull
    @Setter(AccessLevel.NONE)
    private ProposalResearcherId id;

    @NotNull
    @MapsId("proposalId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_proposal_researcher_proposal"))
    @Setter(AccessLevel.NONE)
    private ProposalEntity proposal;

    // Open-ended institutional role code, as specified by the schema.
    @NotNull
    @Size(max = 50)
    @Column(name = "proposal_role", nullable = false, length = 50)
    private String proposalRole;

    @Column(name = "responsibility", columnDefinition = "text")
    private String responsibility;

    @NotNull
    @Column(name = "added_at", nullable = false, columnDefinition = "timestamptz")
    private Instant addedAt;

    @NotNull
    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    public ProposalResearcherEntity(ProposalEntity proposal, UUID userId, String proposalRole,
                                    Instant addedAt, UUID addedBy) {
        this.id = new ProposalResearcherId(proposal.getId(), userId);
        this.proposal = proposal;
        this.proposalRole = proposalRole;
        this.addedAt = addedAt;
        this.addedBy = addedBy;
    }
}
