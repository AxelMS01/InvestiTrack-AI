package com.example.reportservice;

import com.example.reportservice.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportingRulesTests {
    @Test void deadlineBoundariesAndCancelledWork() {
        LocalDate today=LocalDate.of(2026,11,23);
        assertThat(DeadlineState.at("PENDING",today.minusDays(1),today)).isEqualTo("OVERDUE");
        assertThat(DeadlineState.at("PENDING",today,today)).isEqualTo("UPCOMING");
        assertThat(DeadlineState.at("PENDING",today.plusDays(7),today)).isEqualTo("UPCOMING");
        assertThat(DeadlineState.at("PENDING",today.plusDays(8),today)).isEqualTo("FUTURE");
        assertThat(DeadlineState.at("CANCELLED",today.minusDays(1),today)).isEqualTo("COMPLETED");
    }
    @Test void csvQuotesTextAndNeutralizesSpreadsheetFormulas() {
        String csv=new String(Csv.write(List.of("title"),List.of(Map.of("title","a,\"b\"\nc"),Map.of("title","  =1+1"),Map.of("title","\t@SUM(A1)"))),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"a,\"\"b\"\"\nc\"").contains("\"'  =1+1\"").contains("\"'\t@SUM(A1)\"");
    }
}
