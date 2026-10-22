package com.example.proposalservice.infraestructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResearchAreaRepository
        extends JpaRepository<ResearchAreaEntity, UUID> {
}
