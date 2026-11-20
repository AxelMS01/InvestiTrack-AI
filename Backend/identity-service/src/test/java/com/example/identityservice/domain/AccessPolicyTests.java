package com.example.identityservice.domain;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AccessPolicyTests {
    @Test void unknownActionsNeverGrantAccess() {
        assertThat(AccessPolicy.supported("PROJECT_DESTROY","PROJECT")).isFalse();
        assertThat(AccessPolicy.supported("PROJECT_WRITE","DOCUMENT")).isFalse();
    }
    @Test void proposalWritesRequireBothEditableStateAndMembership() {
        assertThat(AccessPolicy.proposal("PROPOSAL_WRITE","SUBMITTED",Set.of("ADMIN"),true,false,false)).isFalse();
        assertThat(AccessPolicy.proposal("PROPOSAL_WRITE","DRAFT",Set.of("ADMIN"),false,false,false)).isFalse();
        assertThat(AccessPolicy.proposal("PROPOSAL_WRITE","CHANGES_REQUESTED",Set.of("RESEARCHER"),false,true,false)).isTrue();
        assertThat(AccessPolicy.proposal("PROPOSAL_READ","DRAFT",Set.of("EVALUATOR"),false,false,true)).isFalse();
    }
    @Test void membershipAloneDoesNotGrantWritesAndClosedProjectsStayImmutable() {
        assertThat(AccessPolicy.project("PROJECT_WRITE","ACTIVE","INTERNAL",Set.of("RESEARCHER"),true,Set.of())).isFalse();
        assertThat(AccessPolicy.project("ACTIVITY_WRITE","ACTIVE","INTERNAL",Set.of("STUDENT"),true,Set.of("ACTIVITY_WRITE"))).isTrue();
        assertThat(AccessPolicy.project("PROJECT_WRITE","CLOSED","INTERNAL",Set.of("ADMIN"),true,Set.of())).isFalse();
    }
    @Test void confidentialProjectsRequireMembershipAndClosureRequiresCompletedState() {
        assertThat(AccessPolicy.project("PROJECT_READ","ACTIVE","CONFIDENTIAL",Set.of("ADMIN"),false,Set.of())).isFalse();
        assertThat(AccessPolicy.project("PROJECT_CLOSE","ACTIVE","INTERNAL",Set.of("COORDINATOR"),true,Set.of())).isFalse();
        assertThat(AccessPolicy.project("PROJECT_CLOSE","COMPLETED","INTERNAL",Set.of("COORDINATOR"),false,Set.of())).isTrue();
    }
}
