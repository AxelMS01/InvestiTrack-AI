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
    name = "proposal_document_reference",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_proposal_document_reference_proposal_id_document_id_purpose", columnNames = {"proposal_id", "document_id", "purpose"})
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalDocumentReferenceEntity extends UuidEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, foreignKey = @ForeignKey(name = "fk_proposal_document_reference_proposal"))
    private ProposalEntity proposal;

    @NotNull
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @NotNull
    @Size(max = 50)
    @Column(name = "purpose", nullable = false, length = 50)
    private String purpose;

    @NotNull
    @Column(name = "linked_at", nullable = false, columnDefinition = "timestamptz")
    private Instant linkedAt;

    @NotNull
    @Column(name = "linked_by", nullable = false)
    private UUID linkedBy;

    public ProposalDocumentReferenceEntity(ProposalEntity proposal, UUID documentId, String purpose, Instant linkedAt, UUID linkedBy) {
        this.proposal = proposal;
        this.documentId = documentId;
        this.purpose = purpose;
        this.linkedAt = linkedAt;
        this.linkedBy = linkedBy;
    }
}
