package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Embeddable
@Getter
@lombok.EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalResearcherId implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    @Column(name = "proposal_id", nullable = false)
    private UUID proposalId;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public ProposalResearcherId(UUID proposalId, UUID userId) {
        this.proposalId = proposalId;
        this.userId = userId;
    }
}
