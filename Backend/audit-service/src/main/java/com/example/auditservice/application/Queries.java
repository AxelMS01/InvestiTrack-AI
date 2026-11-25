package com.example.auditservice.application;

import com.example.auditservice.api.*;
import com.example.auditservice.api.ApiModels.Caller;
import com.example.auditservice.domain.AuditRules;
import com.example.auditservice.infrastructure.AuditStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.example.auditservice.api.ApiException.*;
import static com.example.auditservice.infrastructure.AuditStore.map;

@Service
@Transactional(readOnly=true)
public class Queries {
    private final AuditStore store;
    public static final Set<String> FILTERS=Set.of("actorId","action","resourceType","resourceId","projectId","outcome","from","to","correlationId","sourceService");
    public Queries(AuditStore store){this.store=store;}
    public static void authorize(Caller caller){try{AuditRules.auditor(caller.roles());}catch(SecurityException e){throw new ApiException(403,"AUDITOR_REQUIRED",e.getMessage());}}
    public Map<String,String> filters(Map<String,String> input) {
        require(input!=null&&FILTERS.containsAll(input.keySet())&&input.values().stream().allMatch(v->v!=null&&!v.isBlank()&&v.length()<=255),400,"INVALID_FILTER","Unknown, empty or oversized audit filter.");
        var result=new TreeMap<>(input);
        for(String id:List.of("resourceId","projectId"))if(result.containsKey(id))UUID.fromString(result.get(id));
        if(result.containsKey("outcome"))require(Set.of("SUCCESS","FAILURE","DENIED").contains(result.get("outcome")),400,"INVALID_OUTCOME","Invalid audit outcome.");
        if(result.containsKey("from"))instant(result.get("from"),true);if(result.containsKey("to"))instant(result.get("to"),false);
        if(result.containsKey("from")&&result.containsKey("to"))require(!instant(result.get("from"),true).isAfter(instant(result.get("to"),false)),400,"INVALID_DATE_RANGE","from must be on or before to.");
        return result;
    }
    public Map<String,Object> search(Caller caller,Map<String,String> parameters) {
        authorize(caller);var p=new TreeMap<>(parameters);int limit=Integer.parseInt(p.getOrDefault("limit","25"));String cursor=p.remove("cursor");p.remove("limit");filters(p);
        require(limit>=1&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        String binding=AuditRules.hash(map("caller",caller.id(),"filters",p,"limit",limit));long upper=ceiling();Instant after=null;UUID id=null;
        if(cursor!=null) {
            try {
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                require(parts.length==4&&binding.equals(parts[0]),400,"INVALID_CURSOR","The cursor does not match this caller or query.");
                upper=Long.parseLong(parts[1]);require(upper>=0,400,"INVALID_CURSOR","Invalid snapshot boundary.");after=Instant.parse(parts[2]);id=UUID.fromString(parts[3]);
            }catch(IllegalArgumentException|java.time.DateTimeException e){throw new ApiException(400,"INVALID_CURSOR","The cursor is invalid.");}
        }
        var rows=rows(p,upper,after,id,limit+1);boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));String next=null;
        if(more){var last=rows.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((binding+"|"+upper+"|"+last.get("occurredAt")+"|"+last.get("id")).getBytes(StandardCharsets.UTF_8));}
        return map("items",rows.stream().map(Queries::view).toList(),"page",map("limit",limit,"nextCursor",next,"hasMore",more));
    }
    public Map<String,Object> get(Caller caller,UUID id){authorize(caller);return view(store.row("audit_event",id));}
    public long ceiling(){return ((Number)store.one("select coalesce(max(sequence_number),0) as value from audit_event",Map.of()).orElseThrow().get("value")).longValue();}
    public List<Map<String,Object>> rows(Map<String,String> filters,long upper,Instant after,UUID afterId,int limit) {
        filters(filters);var args=map("upper",upper,"limit",limit);var sql=new StringBuilder("select * from audit_event where sequence_number<=:upper");
        for(var entry:Map.of("actorId","actor_id","action","action","resourceType","resource_type","resourceId","resource_id","projectId","project_id","outcome","outcome","correlationId","correlation_id","sourceService","source_service").entrySet()) {
            if(filters.containsKey(entry.getKey())){sql.append(" and ").append(entry.getValue()).append("=:").append(entry.getKey());args.put(entry.getKey(),Set.of("resourceId","projectId").contains(entry.getKey())?UUID.fromString(filters.get(entry.getKey())):filters.get(entry.getKey()));}
        }
        for(String bound:List.of("from","to"))if(filters.containsKey(bound)){sql.append(" and occurred_at").append(bound.equals("from")?">=":"<=").append(":").append(bound);args.put(bound,instant(filters.get(bound),bound.equals("from")));}
        if(after!=null){sql.append(" and (occurred_at,id)<(:after,:afterId)");args.put("after",after);args.put("afterId",afterId);}
        sql.append(" order by occurred_at desc,id desc limit :limit");return store.query(sql.toString(),args);
    }
    public static Map<String,Object> view(Map<String,Object> row){
        var result=new LinkedHashMap<>(row);result.remove("payloadHash");result.put("actor",map("type",result.remove("actorType"),"id",result.remove("actorId")));result.put("resource",map("type",result.remove("resourceType"),"id",result.remove("resourceId")));return result;
    }
    private static Instant instant(String value,boolean start){return value.length()==10?LocalDate.parse(value).atTime(start?LocalTime.MIN:LocalTime.MAX).toInstant(ZoneOffset.UTC):Instant.parse(value);}
}
