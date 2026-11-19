package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Inbox record; one consumer processes each event in this service. */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEventEntity {

    @Id
    @NotNull
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @NotNull
    @Size(max = 150)
    @Column(name = "consumer_name", nullable = false, length = 150)
    private String consumerName;

    @NotNull
    @Size(max = 150)
    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @NotNull
    @Column(name = "processed_at", nullable = false, columnDefinition = "timestamptz")
    private Instant processedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private ProcessedEventResult result;

    public ProcessedEventEntity(UUID eventId, String consumerName, String eventType,
                                Instant processedAt, ProcessedEventResult result) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.eventType = eventType;
        this.processedAt = processedAt;
        this.result = result;
    }
}
