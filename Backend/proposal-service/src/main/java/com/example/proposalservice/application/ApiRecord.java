package com.example.proposalservice.application;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable HTTP replay records and immutable approval snapshots. */
@Entity
@Table(name="proposal_api_record")
@Getter
@NoArgsConstructor
class ApiRecord {
    @Id @Column(length=200) private String id;
    @Column(nullable=false, length=64) private String requestHash;
    @Column(nullable=false, columnDefinition="text") private String payload;
    ApiRecord(String id,String requestHash,String payload) { this.id=id; this.requestHash=requestHash; this.payload=payload; }
}
