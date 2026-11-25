package com.example.proposalservice.infrastructure;

import com.example.proposalservice.api.*;
import com.example.proposalservice.api.ApiModels.ProjectReference;
import com.example.proposalservice.application.ProposalApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.example.proposalservice.api.ApiException.require;
import static com.example.proposalservice.application.ProposalApplicationService.fields;

@Service
public class ProposalInbox {
    private final IntegrationStore store;
    private final ProposalApplicationService proposals;
    public ProposalInbox(IntegrationStore store,ProposalApplicationService proposals){this.store=store;this.proposals=proposals;}
    @Transactional @SuppressWarnings("unchecked")
    public void accept(byte[] bytes) {
        require(bytes.length<=262144,400,"INVALID_EVENT","Events must not exceed 256 KiB.");Map<String,Object> event;
        try {
            event=store.json().readValue(bytes,Map.class);String type=event.get("eventType").toString();String producer=event.get("producer").toString();
            require((type.equals("ProjectCreated")&&producer.equals("project-execution-service"))||(type.equals("DocumentDeleted")&&producer.equals("document-service")),400,"INVALID_EVENT","Unsupported event type or producer.");
            require(integer(event.get("eventVersion"))==1,400,"INVALID_EVENT","Unsupported event schema version.");UUID.fromString(event.get("eventId").toString());Instant.parse(event.get("occurredAt").toString());
            require(event.get("correlationId").toString().matches("[A-Za-z0-9._:-]{1,100}"),400,"INVALID_EVENT","Invalid correlation ID.");
            var actor=(Map<?,?>)event.get("actor");String actorType=actor.get("type").toString();require(Set.of("USER","SERVICE","SYSTEM").contains(actorType)&&!actor.get("id").toString().isBlank(),400,"INVALID_EVENT","Invalid actor.");if(actorType.equals("USER"))UUID.fromString(actor.get("id").toString());
            var aggregate=(Map<?,?>)event.get("aggregate");require(integer(aggregate.get("version"))>0,400,"INVALID_EVENT","Invalid aggregate version.");UUID.fromString(aggregate.get("id").toString());
            var data=(Map<?,?>)event.get("data");String key=type.equals("ProjectCreated")?"projectId":"documentId";
            UUID.fromString(data.get(key).toString());require(aggregate.get("id").toString().equals(data.get(key).toString())&&aggregate.get("type").equals(type.equals("ProjectCreated")?"PROJECT":"DOCUMENT"),400,"INVALID_EVENT","Event aggregate does not match its resource.");
            if(type.equals("ProjectCreated")) {
                for(String id:List.of("proposalId","approvalId","principalResearcherId","coordinatorId"))UUID.fromString(data.get(id).toString());
                require("APPROVED".equals(data.get("status")),400,"INVALID_EVENT","ProjectCreated must describe initial APPROVED state.");java.time.LocalDate.parse(data.get("plannedEndDate").toString());
            }else {require(integer(data.get("accessVersion"))>0,400,"INVALID_EVENT","Invalid access version.");Instant.parse(data.get("deletedAt").toString());}
        }catch(ApiException e){throw e;}catch(RuntimeException e){throw new ApiException(400,"INVALID_EVENT","The event does not match its contract.");}
        UUID id=UUID.fromString(event.get("eventId").toString());store.lock("proposal-incoming:"+id);
        var old=store.one("select payload from proposal_incoming_event where id=:id",fields("id",id));
        if(old.isPresent()){require(store.json().readTree(store.json().writeValueAsString(old.get().get("payload"))).equals(store.json().readTree(store.json().writeValueAsString(event))),409,"EVENT_CONFLICT","The event ID has contradictory contents.");return;}
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
        store.insert("proposal_incoming_event",fields("id",id,"eventType",event.get("eventType"),"payload",event,"payloadHash",hash,"status","PENDING","receivedAt",Instant.now()));
    }
    @Transactional @SuppressWarnings("unchecked")
    public void process(UUID id) {
        var incoming=store.row("proposal_incoming_event",id,true);if(!"PENDING".equals(incoming.get("status")))return;
        var event=(Map<String,Object>)incoming.get("payload");var data=(Map<String,Object>)event.get("data");
        EventOrigin.run(event,()->{
            if("ProjectCreated".equals(event.get("eventType"))) {
                UUID proposal=UUID.fromString(data.get("proposalId").toString()),approval=UUID.fromString(data.get("approvalId").toString());var snapshot=proposals.approved(proposal,approval);var plan=(Map<?,?>)snapshot.get("creationPlan");
                require(Objects.toString(snapshot.get("principalResearcherId")).equals(data.get("principalResearcherId").toString())&&Objects.toString(plan.get("coordinatorId")).equals(data.get("coordinatorId").toString())&&Objects.toString(plan.get("plannedEndDate")).equals(data.get("plannedEndDate").toString()),409,"PROJECT_SNAPSHOT_CONFLICT","ProjectCreated does not match the approved creation plan.");
                proposals.projectReference(proposal,new ProjectReference(approval,UUID.fromString(data.get("projectId").toString())));
            }else proposals.documentDeleted(data);
        });
        store.insert("processed_event",fields("eventId",id,"consumerName","proposal-domain-events","eventType",event.get("eventType"),"processedAt",Instant.now(),"result","SUCCEEDED"));
        store.update("proposal_incoming_event",id,fields("status","SUCCEEDED","lastErrorCode",null,"nextAttemptAt",null));
    }
    private static long integer(Object value){require(value instanceof Integer||value instanceof Long,400,"INVALID_EVENT","Event versions must be integers.");return ((Number)value).longValue();}
}
