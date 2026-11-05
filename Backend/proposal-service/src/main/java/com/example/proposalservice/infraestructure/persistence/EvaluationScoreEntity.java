package com.example.proposalservice.infraestructure.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
    name = "evaluation_score",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_evaluation_score_evaluation_id_criterion_id", columnNames = {"evaluation_id", "criterion_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationScoreEntity extends AuditedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evaluation_score_evaluation"))
    private EvaluationEntity evaluation;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criterion_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evaluation_score_criterion"))
    private EvaluationCriterionEntity criterion;

    @NotNull
    @Size(max = 50)
    @Column(name = "criterion_code_snapshot", nullable = false, length = 50)
    private String criterionCodeSnapshot;

    @NotNull
    @Size(max = 200)
    @Column(name = "criterion_name_snapshot", nullable = false, length = 200)
    private String criterionNameSnapshot;

    @NotNull
    @Digits(integer = 3, fraction = 3)
    @Column(name = "weight_snapshot", nullable = false, precision = 6, scale = 3)
    private BigDecimal weightSnapshot;

    @NotNull
    @Digits(integer = 4, fraction = 2)
    @Column(name = "score", nullable = false, precision = 6, scale = 2)
    private BigDecimal score;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    public EvaluationScoreEntity(EvaluationEntity evaluation, EvaluationCriterionEntity criterion, String criterionCodeSnapshot, String criterionNameSnapshot, BigDecimal weightSnapshot, BigDecimal score, UUID actorUserId) {
        super(actorUserId);
        this.evaluation = evaluation;
        this.criterion = criterion;
        this.criterionCodeSnapshot = criterionCodeSnapshot;
        this.criterionNameSnapshot = criterionNameSnapshot;
        this.weightSnapshot = weightSnapshot;
        this.score = score;
    }
}
