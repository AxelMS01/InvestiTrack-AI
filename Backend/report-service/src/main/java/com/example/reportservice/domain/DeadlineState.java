package com.example.reportservice.domain;

import java.time.LocalDate;
import java.util.Set;

public final class DeadlineState {
    private DeadlineState() {}
    public static String at(String status, LocalDate deadline, LocalDate today) {
        if(Set.of("COMPLETED","CANCELLED").contains(status))return "COMPLETED";
        if(deadline.isBefore(today))return "OVERDUE";
        return deadline.isAfter(today.plusDays(7))?"FUTURE":"UPCOMING";
    }
}
