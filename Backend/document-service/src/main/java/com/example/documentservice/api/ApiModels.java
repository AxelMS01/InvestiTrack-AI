package com.example.documentservice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ApiModels {
    private ApiModels() { }
    public enum Classification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum ScanResult { CLEAN, INFECTED, ERROR }
    public enum ExtractionStatus { SUCCEEDED, FAILED }
    public enum IndexStatus { INDEXED, FAILED }
    public enum Purpose { EVIDENCE, FINAL_REPORT }
    public record Caller(UUID id,Set<String> roles) { }
    public record Context(UUID proposalId,UUID projectId,UUID activityId,UUID milestoneId) {
        public Map<String,Object> values(){var m=new LinkedHashMap<String,Object>();if(proposalId!=null)m.put("proposalId",proposalId);if(projectId!=null)m.put("projectId",projectId);if(activityId!=null)m.put("activityId",activityId);if(milestoneId!=null)m.put("milestoneId",milestoneId);return m;}
    }
    public record Upload(@NotBlank @Size(max=500) String fileName,@NotBlank String contentType,@Positive long sizeBytes,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256,@NotBlank @Size(max=50) String documentType,
        @NotNull Classification classification,@NotNull @Valid Context context) { }
    public record Patch(@Size(min=1,max=500) String logicalName,Classification classification) { }
    public record Scan(@NotNull UUID jobId,@Positive int documentVersion,@Pattern(regexp="[0-9a-f]{64}") @NotNull String sha256,
        @NotNull ScanResult result,@NotBlank @Size(max=100) String engine,@NotBlank @Size(max=100) String signatureVersion,@NotNull Instant scannedAt) { }
    public record Extraction(@NotNull UUID jobId,@Positive int documentVersion,@NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256,
        @NotNull ExtractionStatus status,@Size(max=500) String contentRef,@Pattern(regexp="[0-9a-f]{64}") String textSha256,
        @PositiveOrZero Integer segmentCount,@Size(max=100) String errorCode) { }
    public record Indexing(@Positive int documentVersion,@NotNull UUID extractionId,@NotBlank @Size(max=100) String indexVersion,
        @NotNull IndexStatus status,Instant indexedAt,@Size(max=100) String errorCode) { }
    public record Source(@NotNull UUID subjectId,@Positive int documentVersion,@Positive long expectedAccessVersion) { }
    public record Reference(@NotNull UUID projectId,@Positive int documentVersion,@NotNull Purpose purpose) { }
    public record Association(@NotNull @Valid Context context,@NotBlank @Size(max=50) String associationType) { }
}
