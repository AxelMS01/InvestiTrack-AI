package com.example.projectservice.domain;

import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static com.example.projectservice.api.ApiException.require;

public final class ProjectRules {
    private ProjectRules() { }
    private static final Map<String,Set<String>> PROJECT=Map.of(
        "APPROVED",Set.of("ACTIVE","CANCELLED"),"ACTIVE",Set.of("DELAYED","COMPLETED","CANCELLED"),
        "DELAYED",Set.of("ACTIVE","COMPLETED","CANCELLED"),"COMPLETED",Set.of("CLOSED","ACTIVE"));
    private static final Map<String,Set<String>> ACTIVITY=Map.of(
        "NOT_STARTED",Set.of("IN_PROGRESS","CANCELLED"),"IN_PROGRESS",Set.of("BLOCKED","COMPLETED","CANCELLED"),
        "BLOCKED",Set.of("IN_PROGRESS","CANCELLED"),"COMPLETED",Set.of(),"CANCELLED",Set.of());
    private static final Map<String,Set<String>> MILESTONE=Map.of(
        "PLANNED",Set.of("IN_PROGRESS","DELAYED","CANCELLED"),"IN_PROGRESS",Set.of("COMPLETED","DELAYED","CANCELLED"),
        "DELAYED",Set.of("IN_PROGRESS","COMPLETED","CANCELLED"),"COMPLETED",Set.of(),"CANCELLED",Set.of());
    public static void transition(String kind,String from,String to) {
        var rules=switch(kind) { case "project"->PROJECT; case "activity"->ACTIVITY; case "milestone"->MILESTONE; default->throw new IllegalArgumentException(); };
        require(rules.getOrDefault(from,Set.of()).contains(to),409,"TRANSITION_NOT_ALLOWED","The requested state transition is not allowed.");
    }
    public static void editable(String status) {
        require(!Set.of("CLOSED","CANCELLED").contains(status),409,"PROJECT_NOT_EDITABLE","Closed and cancelled projects cannot be changed.");
    }
    public static void etag(String header,long version) {
        require(header!=null,428,"IF_MATCH_REQUIRED","Supply the current ETag in If-Match.");
        require(header.matches("\"[1-9][0-9]*\""),400,"INVALID_IF_MATCH","If-Match must contain one quoted numeric version.");
        require(header.equals("\""+version+"\""),412,"STALE_VERSION","The resource changed. Retrieve its current ETag.");
    }
    public static void dates(LocalDate start,LocalDate end) {
        require(end!=null && (start==null || !end.isBefore(start)),422,"INVALID_DATES","The deadline must be on or after the start date.");
    }
    public static void text(String value) { require(value!=null&&!value.isBlank(),422,"INVALID_NAME","A nonblank name is required."); }
    public record Contribution(UUID id,String status,BigDecimal weight) { }
    public record Progress(BigDecimal percent,BigDecimal completedWeight,BigDecimal totalWeight,List<Contribution> contributions) { }
    public static Progress progress(List<Contribution> contributions) {
        BigDecimal total=BigDecimal.ZERO, completed=BigDecimal.ZERO;
        for(var item:contributions) {
            require(item.weight()!=null&&item.weight().signum()>0,422,"INVALID_WEIGHT","Weights must be positive.");
            if(item.status().equals("CANCELLED")) continue;
            total=total.add(item.weight());
            if(item.status().equals("COMPLETED")) completed=completed.add(item.weight());
        }
        BigDecimal percent=total.signum()==0?BigDecimal.ZERO.setScale(2):completed.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);
        return new Progress(percent,completed,total,List.copyOf(contributions));
    }
}
