package com.example.proposalservice.infraestructure.persistence;

import jakarta.validation.Validation;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProposalPersistenceModelsTests {

    @Test
    void allModelsBuildTogetherWithoutDatabaseAccess() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        try {
            var sources = new MetadataSources(registry);
            for (Class<?> entity : new Class<?>[]{
                    ResearchAreaEntity.class, ProposalEntity.class, ProposalObjectiveEntity.class,
                    ProposalResearcherEntity.class, ProposalDocumentReferenceEntity.class,
                    ProposalStatusHistoryEntity.class, EvaluatorAssignmentEntity.class,
                    EvaluationCriterionEntity.class, EvaluationEntity.class, EvaluationScoreEntity.class,
                    ProposalDecisionEntity.class, OutboxEventEntity.class, ProcessedEventEntity.class
            }) {
                sources.addAnnotatedClass(entity);
            }
            // Building a factory resolves relationships, inherited attributes and composite identifiers.
            try (var factory = sources.buildMetadata().buildSessionFactory()) {
                assertEquals(13, factory.getMetamodel().getEntities().size());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void researcherIdentityIncludesBothProposalAndExternalUser() {
        UUID proposalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var id = new ProposalResearcherId(proposalId, userId);
        var sameId = new ProposalResearcherId(proposalId, userId);
        assertEquals(id, sameId);
        assertEquals(id.hashCode(), sameId.hashCode());
        assertNotEquals(id, new ProposalResearcherId(UUID.randomUUID(), userId));
        assertNotEquals(id, new ProposalResearcherId(proposalId, UUID.randomUUID()));
    }

    @Test
    void auditedRecordsInitializeTimestampsAndPreserveCreationAuditOnUpdate() {
        UUID actor = UUID.randomUUID();
        var objective = new ProposalObjectiveEntity(
                new ProposalEntity("P-1", "Research", actor, actor), ObjectiveType.GENERAL, "Objective", 0, actor);
        objective.onCreate();
        Instant createdAt = objective.getCreatedAt();
        assertNotNull(createdAt);
        assertEquals(createdAt, objective.getUpdatedAt());
        assertEquals(actor, objective.getCreatedBy());
        UUID editor = UUID.randomUUID();
        objective.setUpdatedBy(editor);
        objective.onUpdate();
        assertEquals(createdAt, objective.getCreatedAt());
        assertFalse(objective.getUpdatedAt().isBefore(createdAt));
        assertEquals(actor, objective.getCreatedBy());
        assertEquals(editor, objective.getUpdatedBy());
    }

    @Test
    void rubricWeightMustBePositiveAndFitDatabasePrecision() {
        UUID actor = UUID.randomUUID();
        var criterion = new EvaluationCriterionEntity("Quality", "Research quality", BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("1.000"), LocalDate.now(), actor);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(criterion).isEmpty());
            criterion.setWeight(BigDecimal.ZERO);
            assertFalse(validator.validate(criterion).isEmpty());
            criterion.setWeight(new BigDecimal("1000.000"));
            assertFalse(validator.validate(criterion).isEmpty());
            criterion.setWeight(new BigDecimal("0.001"));
            assertTrue(validator.validate(criterion).isEmpty());
        }
    }
}
