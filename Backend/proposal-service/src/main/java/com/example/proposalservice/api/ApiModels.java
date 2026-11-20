package com.example.proposalservice.api;

import com.example.proposalservice.infraestructure.persistence.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}
    public record Objective(@NotNull ObjectiveType type, @NotBlank String description) {}
    public record ProposalInput(@Size(max=500) String title, @JsonProperty("abstract") String abstractText,
        @Size(max=50000) String researchProblem, @Size(max=100) List<@NotNull @Valid Objective> objectives, @Size(max=50000) String methodology,
        @Size(max=50) String researchAreaCode, @Size(max=100) List<@NotNull UUID> researcherIds,
        @Size(max=50000) String expectedResults, @Positive Integer estimatedDurationMonths,
        @Size(max=100) List<@NotNull UUID> supportingDocumentIds) {}
    public record CreationPlan(@NotNull UUID coordinatorId, LocalDate startDate, @NotNull LocalDate plannedEndDate) {}
    public record Transition(@NotNull ProposalStatus targetStatus, @Size(max=2000) String reason, @Valid CreationPlan creationPlan) {
        public Transition(ProposalStatus targetStatus,String reason){this(targetStatus,reason,null);}
    }
    public record ResearcherInput(UUID userId, @Size(max=50) String proposalRole, @Size(max=2000) String responsibility) {}
    public record AssignmentInput(UUID evaluatorId, LocalDate dueDate, @Size(max=2000) String instructions) {}
    public record Score(@NotBlank String criterionCode, @NotNull @Digits(integer=4,fraction=2) BigDecimal score, String comment) {}
    public record EvaluationInput(@Size(max=100) List<@NotNull @Valid Score> criteria, @Size(max=50000) String observations,
        @Size(max=50000) String recommendations, EvaluationDecision decision) {}
    public record ProjectReference(@NotNull UUID approvalId, @NotNull UUID projectId) {}
    public record Page(int limit, String nextCursor, boolean hasMore) {}
    public record CollectionResponse<T>(List<T> items, Page page) {}
}
