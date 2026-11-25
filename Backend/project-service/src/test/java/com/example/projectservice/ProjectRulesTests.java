package com.example.projectservice;

import com.example.projectservice.api.ApiException;
import com.example.projectservice.domain.ProjectRules;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProjectRulesTests {
    @Test void progressUsesDecimalWeightsAndExcludesCancelledContributions() {
        var calculation=ProjectRules.progress(List.of(new ProjectRules.Contribution(UUID.randomUUID(),"COMPLETED",new BigDecimal("0.1")),
            new ProjectRules.Contribution(UUID.randomUUID(),"IN_PROGRESS",new BigDecimal("0.2")),new ProjectRules.Contribution(UUID.randomUUID(),"CANCELLED",new BigDecimal("999"))));
        assertThat(calculation.percent()).isEqualByComparingTo("33.33");assertThat(calculation.totalWeight()).isEqualByComparingTo("0.3");
    }
    @Test void noWorkAndAllCancelledWorkProduceZeroRatherThanFalseCompletion() {
        assertThat(ProjectRules.progress(List.of()).percent()).isEqualByComparingTo("0");
        assertThat(ProjectRules.progress(List.of(new ProjectRules.Contribution(UUID.randomUUID(),"CANCELLED",BigDecimal.TEN))).percent()).isEqualByComparingTo("0");
    }
    @Test void terminalStatesCannotBeReopenedThroughOrdinaryTransitions() {
        for(String status:List.of("CLOSED","CANCELLED"))assertThatThrownBy(()->ProjectRules.transition("project",status,"ACTIVE")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->ProjectRules.transition("activity","COMPLETED","IN_PROGRESS")).isInstanceOf(ApiException.class);
        assertThatCode(()->ProjectRules.transition("project","COMPLETED","ACTIVE")).doesNotThrowAnyException();
    }
    @Test void weakWildcardAndStaleEtagsNeverPassConcurrencyChecks() {
        for(String invalid:List.of("*","W/\"1\"","1","\"1\",\"2\""))assertThatThrownBy(()->ProjectRules.etag(invalid,1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->ProjectRules.etag("\"1\"",2)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(412);
        assertThatThrownBy(()->ProjectRules.etag(null,1)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(428);
    }
}
