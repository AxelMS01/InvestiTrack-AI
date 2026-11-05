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
        String researchProblem, List<@Valid Objective> objectives, String methodology,
        @Size(max=50) String researchAreaCode, @Size(max=100) List<@NotNull UUID> researcherIds,
        String expectedResults, @Positive Integer estimatedDurationMonths,
        @Size(max=100) List<@NotNull UUID> supportingDocumentIds) {}
    public record Transition(@NotNull ProposalStatus targetStatus, String reason) {}
    public record ResearcherInput(UUID userId, @Size(max=50) String proposalRole, String responsibility) {}
    public record AssignmentInput(UUID evaluatorId, LocalDate dueDate, String instructions) {}
    public record Score(@NotBlank String criterionCode, @NotNull @Digits(integer=4,fraction=2) BigDecimal score, String comment) {}
    public record EvaluationInput(@Size(max=100) List<@Valid Score> criteria, String observations,
        String recommendations, EvaluationDecision decision) {}
    public record ProjectReference(@NotNull UUID approvalId, @NotNull UUID projectId) {}
    public record Page(int limit, String nextCursor, boolean hasMore) {}
    public record CollectionResponse<T>(List<T> items, Page page) {}
}
