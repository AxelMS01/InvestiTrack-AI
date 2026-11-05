package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "evaluation_criterion",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_evaluation_criterion_code", columnNames = {"code"})
    }
)
@org.hibernate.annotations.Check(constraints = "(weight > 0)")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationCriterionEntity extends AuditedEntity {

    @Size(max = 50)
    @Column(name = "code", length = 50)
    private String code;

    @NotNull
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @NotNull
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @NotNull
    @Digits(integer = 4, fraction = 2)
    @Column(name = "minimum_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal minimumScore;

    @NotNull
    @Digits(integer = 4, fraction = 2)
    @Column(name = "maximum_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal maximumScore;

    @NotNull
    @Positive
    @Digits(integer = 3, fraction = 3)
    @Column(name = "weight", nullable = false, precision = 6, scale = 3)
    private BigDecimal weight;

    @NotNull
    @Column(name = "active_from", nullable = false)
    private LocalDate activeFrom;

    @Column(name = "active_until")
    private LocalDate activeUntil;

    public EvaluationCriterionEntity(String name, String description, BigDecimal minimumScore, BigDecimal maximumScore, BigDecimal weight, LocalDate activeFrom, UUID actorUserId) {
        super(actorUserId);
        this.name = name;
        this.description = description;
        this.minimumScore = minimumScore;
        this.maximumScore = maximumScore;
        this.weight = weight;
        this.activeFrom = activeFrom;
    }
}
