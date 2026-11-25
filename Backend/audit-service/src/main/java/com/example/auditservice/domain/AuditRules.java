package com.example.auditservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** No persistence or HTTP dependencies. Only structural, bounded metadata is retained. */
public final class AuditRules {
    private AuditRules() { }
    private static final Set<String> IDS=Set.of("projectId","proposalId","documentId","activityId","milestoneId","userId","approvalId","assignmentId","evaluationId","publicationId","interactionId","feedbackId","extractionId","jobId","referenceId","subjectId","principalResearcherId","coordinatorId");
    private static final Set<String> NUMBERS=Set.of("documentVersion","accessVersion","identityVersion","aggregateVersion","sourceCount","durationMs","progress","eventVersion","sourceVersion","attemptCount","recordCount");
    private static final Set<String> CODES=Set.of("status","change","operation","classification","purpose","outcome","reasonCode","rating","resourceType","policyVersion","modelConfigId");
    private static final Set<String> TIMES=Set.of("submittedAt","approvedAt","closedAt","deletedAt","detectedAt","indexedAt","sentAt","checkedAt");
    public static Map<String,Object> metadata(Map<String,?> input) {
        var result=new TreeMap<String,Object>();if(input==null)return result;
        if(input.size()>100)throw new IllegalArgumentException("Too many metadata fields");
        input.forEach((key,value)->{
            if(value==null)return;
            if(IDS.contains(key))result.put(key,UUID.fromString(value.toString()).toString());
            else if(NUMBERS.contains(key)) {
                if(!(value instanceof Number))throw new IllegalArgumentException("Numeric metadata required");
                var number=new BigDecimal(value.toString());if(number.signum()<0||number.compareTo(new BigDecimal("1000000000000000"))>0||number.scale()>6)throw new IllegalArgumentException("Invalid numeric metadata");
                if(key.equals("progress")&&number.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Invalid progress");
                result.put(key,number.stripTrailingZeros());
            } else if(CODES.contains(key)) {
                if(!(value instanceof String s)||!s.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("Invalid metadata code");result.put(key,value);
            } else if(TIMES.contains(key))result.put(key,Instant.parse(value.toString()).toString());
            else if(Set.of("dueDate","plannedEndDate").contains(key))result.put(key,LocalDate.parse(value.toString()).toString());
            else if(key.equals("active")) {if(!(value instanceof Boolean))throw new IllegalArgumentException("Invalid active flag");result.put(key,value);}
            else if(key.equals("sha256")) {if(!value.toString().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid checksum");result.put(key,value);}
            else if(key.equals("roles")) {
                if(!(value instanceof List<?> roles)||roles.size()>20||roles.stream().anyMatch(r->!(r instanceof String)||!r.toString().matches("[A-Z][A-Z0-9_]{0,49}")))throw new IllegalArgumentException("Invalid role codes");
                result.put(key,value);
            }
            // Everything else, including nested structures, raw content, credentials and URLs, is omitted.
        });return result;
    }
    public static String hash(Object value) {return sha256(canonical(value));}
    public static String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** Length-prefixed UTF-8 canonical encoding; distinguishes nulls, scalars, maps and ordered lists. */
    public static String canonical(Object value) {
        if(value==null)return "n";
        if(value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),v));var out=new StringBuilder("m"+sorted.size()+":");sorted.forEach((k,v)->out.append(canonical(k)).append(canonical(v)));return out.toString();
        }
        if(value instanceof Collection<?> list){var out=new StringBuilder("l"+list.size()+":");list.forEach(v->out.append(canonical(v)));return out.toString();}
        if(value instanceof Number number)return "d"+new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()+";";
        if(value instanceof Boolean b)return b?"t":"f";
        String s=value.toString();return "s"+s.getBytes(StandardCharsets.UTF_8).length+":"+s;
    }
    public static void auditor(Set<String> roles) {if(roles.stream().noneMatch(Set.of("ADMIN","AUDITOR")::contains))throw new SecurityException("An administrator or authorized auditor is required.");}
}
