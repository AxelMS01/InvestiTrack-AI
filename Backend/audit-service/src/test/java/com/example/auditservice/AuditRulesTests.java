package com.example.auditservice;

import com.example.auditservice.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuditRulesTests {
    @Test void canonicalHashIsOrderedByMapKeysPreservesListOrderAndNormalizesNumbers(){
        var a=new LinkedHashMap<String,Object>();a.put("b",new BigDecimal("1.00"));a.put("a","é");
        var b=new LinkedHashMap<String,Object>();b.put("a","é");b.put("b",1);
        assertThat(AuditRules.hash(a)).isEqualTo(AuditRules.hash(b));assertThat(AuditRules.hash(List.of("a","b"))).isNotEqualTo(AuditRules.hash(List.of("b","a")));
        assertThat(AuditRules.hash("null")).isNotEqualTo(AuditRules.hash(null));assertThat(AuditRules.hash(1)).isNotEqualTo(AuditRules.hash("1"));
    }
    @Test void onlyTypedSafeMetadataSurvives(){
        UUID id=UUID.randomUUID();var input=new LinkedHashMap<String,Object>();input.put("projectId",id.toString());input.put("progress",42.5);input.put("status","AVAILABLE");input.put("documentName","Research secret");input.put("content",Map.of("token","credential"));input.put("url","https://sensitive");input.put("roles",List.of("RESEARCHER"));
        var sanitized=AuditRules.metadata(input);assertThat(sanitized).containsOnlyKeys("projectId","progress","status","roles");
        assertThatThrownBy(()->AuditRules.metadata(Map.of("projectId","not-a-uuid"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AuditRules.metadata(Map.of("status","https://secret"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AuditRules.metadata(Map.of("progress",101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AuditRules.metadata(Map.of("roles",List.of("secret/password")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void csvEscapesQuotesNewlinesAndSpreadsheetFormulas(){
        String csv=new String(Csv.write(List.of("action","metadata"),List.of(Map.of("action","=HYPERLINK(\"secret\")","metadata","line1\nline2"))),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"secret\"\")\"","\"line1\nline2\"");
    }
}
