package com.example.auditservice.application;

import com.example.auditservice.api.*;
import com.example.auditservice.api.ApiModels.*;
import com.example.auditservice.domain.AuditRules;
import com.example.auditservice.infrastructure.AuditStore;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.example.auditservice.api.ApiException.*;
import static com.example.auditservice.infrastructure.AuditStore.map;

@Service
@Transactional
public class Ingestion {
    public static final Map<String,String> PRODUCERS=Map.of("identity","identity-service","proposal","proposal-service","project","project-execution-service","document","document-service","ai","ai-service","reporting","reporting-service");
    private static final Map<String,Set<String>> RESOURCES=Map.of(
        "identity-service",Set.of("USER","ROLE","POLICY","AUTHORIZATION_POLICY"),"proposal-service",Set.of("PROPOSAL","EVALUATION","EVALUATOR_ASSIGNMENT"),
        "project-execution-service",Set.of("PROJECT","ACTIVITY","MILESTONE","PUBLICATION","FINAL_REPORT"),"document-service",Set.of("DOCUMENT","UPLOAD_SESSION"),
        "ai-service",Set.of("AI_INTERACTION","INTERACTION","CONVERSATION","AI_CONVERSATION","AI_MESSAGE","AI_FEEDBACK"),"reporting-service",Set.of("REPORT_JOB","PROJECTION","REBUILD"),
        "idp-audit-adapter",Set.of("USER","AUTHENTICATION"));
    private final AuditStore store;private final Validator validator;
    public record Accepted(UUID eventId,boolean created) { }
    public Ingestion(AuditStore store,Validator validator){this.store=store;this.validator=validator;}
    public Accepted append(AuditInput input,String producer){
        require(validator.validate(input).isEmpty(),400,"INVALID_AUDIT_EVENT","Invalid or missing audit fields.");
        return persist(input,producer,AuditRules.hash(store.json().convertValue(input,Map.class)),input.action());
    }
    @SuppressWarnings("unchecked")
    public Accepted consume(byte[] bytes,String routingKey) {
        require(bytes.length>0&&bytes.length<=256*1024,400,"INVALID_EVENT_SIZE","An event must fit in 256 KiB.");
        try {
            var e=store.json().readValue(bytes,Map.class);String[] route=routingKey.split("\\.");
            require(route.length==3&&PRODUCERS.containsKey(route[0])&&route[2].equals("v1"),400,"INVALID_EVENT_ROUTE","Unsupported event routing key.");
            String producer=PRODUCERS.get(route[0]);String type=text(e,"eventType");
            require(type.matches("[A-Z][A-Za-z0-9_]{0,99}")&&type.equals(route[1])&&producer.equals(e.get("producer")),400,"PRODUCER_MISMATCH","Producer and event type must match the broker route.");
            require(e.get("eventVersion") instanceof Number n&&new java.math.BigDecimal(n.toString()).compareTo(java.math.BigDecimal.ONE)==0,400,"UNSUPPORTED_EVENT_VERSION","Only event version 1 is supported.");
            var aggregate=(Map<String,Object>)e.get("aggregate");var actor=(Map<String,Object>)e.get("actor");var data=(Map<String,Object>)e.get("data");
            require(aggregate!=null&&actor!=null&&data!=null&&aggregate.get("version") instanceof Number n&&new java.math.BigDecimal(n.toString()).scale()<=0&&n.longValue()>0,400,"INVALID_EVENT","Missing aggregate, actor or event data.");
            var metadata=new LinkedHashMap<String,Object>(data);metadata.put("aggregateVersion",aggregate.get("version"));
            UUID aggregateId=UUID.fromString(text(aggregate,"id"));String resourceType=text(aggregate,"type");
            if(resourceType.equals("PROJECT")) {
                if(data.containsKey("projectId"))require(aggregateId.toString().equals(data.get("projectId").toString()),400,"RESOURCE_MISMATCH","The project must match its aggregate.");
                metadata.put("projectId",aggregateId.toString());
            }
            String outcome=type.equals("DocumentScanFailed")?"FAILURE":type.equals("AiInsufficientInformation")?"FAILURE":"SUCCESS";
            if(type.startsWith("Ai")&&data.get("outcome") instanceof String result){outcome=Set.of("DENIED","FAILED").contains(result)?result.equals("DENIED")?"DENIED":"FAILURE":outcome;}
            var input=new AuditInput(UUID.fromString(text(e,"eventId")),Instant.parse(text(e,"occurredAt")),new Actor(ActorType.valueOf(text(actor,"type")),text(actor,"id")),
                type.replaceAll("([a-z0-9])([A-Z])","$1_$2").toUpperCase(Locale.ROOT),new Resource(resourceType,aggregateId),Outcome.valueOf(outcome),text(e,"correlationId"),metadata);
            require(validator.validate(input).isEmpty(),400,"INVALID_EVENT","Invalid audit envelope fields.");
            return persist(input,producer,AuditRules.hash(e),type);
        }catch(ApiException e){throw e;}catch(IllegalArgumentException|NullPointerException|ClassCastException|java.time.DateTimeException|tools.jackson.core.JacksonException e){throw new ApiException(400,"INVALID_EVENT","The event envelope contains invalid or missing fields.");}
    }
    private Accepted persist(AuditInput input,String producer,String payloadHash,String eventType) {
        require(RESOURCES.containsKey(producer)&&RESOURCES.get(producer).contains(input.resource().type()),403,"PRODUCER_RESOURCE_DENIED","This producer cannot attest this resource type.");
        require(!input.occurredAt().isAfter(Instant.now().plusSeconds(60)),422,"INVALID_EVENT_TIME","The occurrence time cannot be in the future.");
        if(input.actor().type()==ActorType.USER)UUID.fromString(input.actor().id());
        if(input.actor().type()==ActorType.SERVICE)require(input.actor().id().equals(producer),400,"INVALID_ACTOR","A service actor must match the attesting producer.");
        if(input.actor().type()==ActorType.SYSTEM)require(Set.of("system","scheduler","keycloak",producer).contains(input.actor().id()),400,"INVALID_ACTOR","Unknown system actor.");
        var metadata=AuditRules.metadata(input.metadata());
        if(input.resource().type().equals("PROJECT")) {
            require(input.resource().id()!=null&&(!metadata.containsKey("projectId")||input.resource().id().toString().equals(metadata.get("projectId"))),400,"RESOURCE_MISMATCH","The project scope must match its resource.");
            metadata.put("projectId",input.resource().id().toString());
        }
        // This global transaction lock serializes only small append operations, never network calls.
        store.lock("audit-chain-v1");
        var previous=store.one("select * from audit_event where id=:id",map("id",input.eventId()));
        if(previous.isPresent()) {
            require(producer.equals(previous.get().get("sourceService"))&&payloadHash.equals(previous.get().get("payloadHash")),409,"AUDIT_EVENT_CONFLICT","The event ID was already accepted with different content or producer.");
            return new Accepted(input.eventId(),false);
        }
        var head=store.one("select event_hash from audit_event order by sequence_number desc limit 1",Map.of());
        long sequence=((Number)store.one("select nextval('audit_sequence') as value",Map.of()).orElseThrow().get("value")).longValue();
        var row=map("id",input.eventId(),"sequenceNumber",sequence,"occurredAt",input.occurredAt().truncatedTo(ChronoUnit.MICROS),"receivedAt",Instant.now().truncatedTo(ChronoUnit.MICROS),
            "actorType",input.actor().type().name(),"actorId",input.actor().id(),"action",input.action(),"resourceType",input.resource().type(),"resourceId",input.resource().id(),
            "projectId",metadata.containsKey("projectId")?UUID.fromString(metadata.get("projectId").toString()):null,"outcome",input.outcome().name(),"correlationId",input.correlationId(),"sourceService",producer,
            "metadata",metadata,"previousHash",head.map(h->h.get("eventHash")).orElse(null),"payloadHash",payloadHash);
        row.put("eventHash",AuditRules.hash(row));store.insert("audit_event",row);
        store.insert("processed_event",map("eventId",input.eventId(),"consumerName","audit-history","eventType",eventType,"result","SUCCEEDED"));
        return new Accepted(input.eventId(),true);
    }
    public static boolean validHash(Map<String,Object> row){var fields=new LinkedHashMap<>(row);Object expected=fields.remove("eventHash");return AuditRules.hash(fields).equals(expected);}
    private static String text(Map<String,?> m,String key){Object value=m.get(key);require(value instanceof String&&!value.toString().isBlank(),400,"INVALID_EVENT","An envelope field is missing or invalid.");return value.toString();}
}
