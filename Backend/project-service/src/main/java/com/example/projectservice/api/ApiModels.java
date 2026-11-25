package com.example.projectservice.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class ApiModels {
    private ApiModels() { }
    public enum ProjectStatus { APPROVED, ACTIVE, DELAYED, COMPLETED, CLOSED, CANCELLED }
    public enum Classification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum ProgressMethod { ACTIVITY_WEIGHT, MILESTONE_WEIGHT }
    public enum MemberRole { PRINCIPAL_RESEARCHER, RESEARCHER, STUDENT, COLLABORATOR }
    public enum ActivityStatus { NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED }
    public enum MilestoneStatus { PLANNED, IN_PROGRESS, COMPLETED, DELAYED, CANCELLED }
    public enum PublicationType { JOURNAL_ARTICLE, CONFERENCE_PAPER, BOOK, BOOK_CHAPTER, THESIS, DATASET, OTHER }
    public record CreateProject(@NotNull UUID proposalId, UUID approvalId, LocalDate startDate,
                                LocalDate plannedEndDate, UUID coordinatorId) { }
    public record PatchProject(@Size(min=1,max=500) String title, @Size(max=50000) String abstractText,
                               LocalDate startDate, LocalDate plannedEndDate, UUID coordinatorId,
                               Classification classification, ProgressMethod progressMethod) { }
    public record ProjectTransition(@NotNull ProjectStatus targetStatus, @Size(max=2000) String reason) { }
    public record Member(@NotNull UUID userId, @NotNull MemberRole projectRole,
                         @Size(max=100) List<@NotBlank @Size(max=100) String> permissions) { }
    public record PatchMember(@NotNull MemberRole projectRole,
                              @NotNull @Size(max=100) List<@NotBlank @Size(max=100) String> permissions) { }
    public record ActivityInput(@NotBlank @Size(max=250) String name, @Size(max=50000) String description,
            @NotNull UUID responsibleUserId, @NotNull LocalDate startDate, @NotNull LocalDate deadline,
            UUID milestoneId, @Digits(integer=3,fraction=3) @DecimalMin("0.001") @DecimalMax("999.999") BigDecimal weight) { }
    public record ActivityPatch(@Size(min=1,max=250) String name, @Size(max=50000) String description,
            UUID responsibleUserId, LocalDate startDate, LocalDate deadline, UUID milestoneId,
            Boolean clearMilestone, @Digits(integer=3,fraction=3) @DecimalMin("0.001") @DecimalMax("999.999") BigDecimal weight) { }
    public record ActivityTransition(@NotNull ActivityStatus targetStatus, @Size(max=2000) String reason) { }
    public record MilestoneInput(@NotBlank @Size(max=250) String name, @Size(max=50000) String description,
            @NotNull LocalDate deadline, @Digits(integer=3,fraction=3) @DecimalMin("0.001") @DecimalMax("999.999") BigDecimal weight,
            @PositiveOrZero Integer displayOrder) { }
    public record MilestonePatch(@Size(min=1,max=250) String name, @Size(max=50000) String description,
            LocalDate deadline, @Digits(integer=3,fraction=3) @DecimalMin("0.001") @DecimalMax("999.999") BigDecimal weight,
            @PositiveOrZero Integer displayOrder) { }
    public record MilestoneTransition(@NotNull MilestoneStatus targetStatus, @Size(max=2000) String reason) { }
    public record Evidence(@NotNull UUID documentId, @NotNull @Positive Integer documentVersion,
                           @Size(max=2000) String description) { }
    public record Report(@NotNull UUID documentId, @NotNull @Positive Integer documentVersion) { }
    public record Reason(@NotBlank @Size(max=2000) String reason) { }
    public record PublicationInput(@NotBlank @Size(max=500) String title, @NotNull PublicationType type,
            @NotEmpty @Size(max=100) List<@NotBlank @Size(max=250) String> authors,
            @Size(max=255) String doi, LocalDate publicationDate, @Size(max=2000) String url,
            @Size(max=300) String journalOrVenue, UUID documentId) { }
    public record PublicationPatch(@Size(min=1,max=500) String title, PublicationType type,
            @Size(min=1,max=100) List<@NotBlank @Size(max=250) String> authors,
            @Size(max=255) String doi, LocalDate publicationDate, @Size(max=2000) String url,
            @Size(max=300) String journalOrVenue, UUID documentId) { }
    public record AccessBatch(@NotNull UUID subjectId, @NotEmpty @Size(max=100) List<@NotNull UUID> projectIds) { }
    public enum Purpose { EVIDENCE, FINAL_REPORT, DOCUMENT }
    public record Association(UUID activityId, UUID milestoneId, @NotNull Purpose purpose) { }
    public record Page<T>(List<T> items, PageInfo page) { }
    public record PageInfo(int limit, String nextCursor, boolean hasMore) { }
    public record Caller(UUID id, Set<String> roles, String token) {
        public boolean role(String role) { return roles.contains(role); }
        public boolean manager() { return role("ADMIN") || role("COORDINATOR"); }
    }
}
