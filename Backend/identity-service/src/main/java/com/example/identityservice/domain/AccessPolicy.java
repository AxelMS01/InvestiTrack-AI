package com.example.identityservice.domain;

import java.util.*;

/** Pure policy decisions; facts are supplied exclusively by the authoritative resource owner. */
public final class AccessPolicy {
    private AccessPolicy() {}
    private static final Map<String,Set<String>> ACTIONS=Map.ofEntries(
        Map.entry("PROPOSAL_READ",Set.of("PROPOSAL")),Map.entry("PROPOSAL_WRITE",Set.of("PROPOSAL")),
        Map.entry("PROJECT_READ",Set.of("PROJECT","ACTIVITY","MILESTONE")),Map.entry("PROJECT_WRITE",Set.of("PROJECT")),
        Map.entry("ACTIVITY_WRITE",Set.of("ACTIVITY")),Map.entry("PROJECT_CLOSE",Set.of("PROJECT")),
        Map.entry("DOCUMENT_READ",Set.of("DOCUMENT")),Map.entry("DOCUMENT_UPLOAD",Set.of("PROJECT","PROPOSAL")),
        Map.entry("DOCUMENT_DELETE",Set.of("DOCUMENT")),Map.entry("AI_SOURCE_READ",Set.of("DOCUMENT")));
    public static boolean supported(String action, String type) { return ACTIONS.getOrDefault(action,Set.of()).contains(type); }
    public static boolean proposal(String action, String status, Set<String> roles, boolean owner, boolean collaborator, boolean evaluator) {
        boolean manager=roles.contains("ADMIN")||roles.contains("COORDINATOR");
        if(action.equals("PROPOSAL_READ")||action.equals("DOCUMENT_READ")||action.equals("AI_SOURCE_READ"))
            return manager||owner||collaborator||(evaluator && !status.equals("DRAFT"));
        if(action.equals("PROPOSAL_WRITE")||action.equals("DOCUMENT_UPLOAD")||action.equals("DOCUMENT_DELETE"))
            return Set.of("DRAFT","CHANGES_REQUESTED").contains(status) && (owner||collaborator);
        return false;
    }
    public static boolean project(String action, String status, String classification, Set<String> roles,
                                  boolean member, Set<String> permissions) {
        boolean manager=roles.contains("ADMIN")||roles.contains("COORDINATOR");
        boolean read=action.equals("PROJECT_READ")||action.equals("DOCUMENT_READ")||action.equals("AI_SOURCE_READ");
        boolean scoped=member&&(read||permissions.contains(action));
        // Confidential resources always require explicit current membership.
        if(!member && !classification.equals("INTERNAL") && !classification.equals("PUBLIC")) return false;
        if(read) return manager||scoped;
        if(action.equals("PROJECT_CLOSE")) return status.equals("COMPLETED")&&(manager||scoped);
        if(Set.of("CLOSED","CANCELLED").contains(status)) return false;
        return manager||scoped;
    }
}
