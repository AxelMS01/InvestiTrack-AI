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
    name = "outbox_event",
    indexes = {
        @Index(name = "ix_outbox_event_aggregate_type_aggregate_id", columnList = "aggregate_type, aggregate_id")
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventEntity extends UuidEntity {

    @NotNull
    @Size(max = 100)
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @NotNull
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @NotNull
    @Size(max = 150)
    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @NotNull
    @Column(name = "event_version", nullable = false)
    private Integer eventVersion;

    @NotNull
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @NotNull
    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Size(max = 100)
    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Size(max = 255)
    @Column(name = "actor_id", length = 255)
    private String actorId;

    @Column(name = "published_at", columnDefinition = "timestamptz")
    private Instant publishedAt;

    @NotNull
    @PositiveOrZero
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", columnDefinition = "timestamptz")
    private Instant nextAttemptAt;

    public OutboxEventEntity(String aggregateType, UUID aggregateId, String eventType, Integer eventVersion, String payload, Instant occurredAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }
}
