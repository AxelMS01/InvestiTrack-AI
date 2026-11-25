package com.example.projectservice.infrastructure;

import com.example.projectservice.api.*;
import com.example.projectservice.api.ApiModels.CreateProject;
import com.example.projectservice.application.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.example.projectservice.api.ApiException.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

@Service
public class ApprovalInbox {
    private final ProjectStore store;
    private final RemoteServices remote;
    private final ProjectService projects;
    private final DomainEvents events;
    public ApprovalInbox(ProjectStore store,RemoteServices remote,ProjectService projects,DomainEvents events) {this.store=store;this.remote=remote;this.projects=projects;this.events=events;}
    @Transactional
    @SuppressWarnings("unchecked")
    public void accept(byte[] bytes) {
        require(bytes.length<=262144,400,"INVALID_EVENT","Events must not exceed 256 KiB.");
        Map<String,Object> event;
        try {
            event=store.json().readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
            var type=event.get("eventType").toString();String producer=event.get("producer").toString();
            require((type.equals("ProposalApproved")&&producer.equals("proposal-service"))||(type.equals("DocumentDeleted")&&producer.equals("document-service")),400,"INVALID_EVENT","Unsupported event producer or type.");
            require(integer(event.get("eventVersion"))==1,400,"INVALID_EVENT","Unsupported event version.");
            UUID.fromString(event.get("eventId").toString());Instant.parse(event.get("occurredAt").toString());
            require(event.get("correlationId").toString().matches("[A-Za-z0-9._:-]{1,100}"),400,"INVALID_EVENT","Invalid event correlation identifier.");
            var aggregate=(Map<?,?>)event.get("aggregate");UUID.fromString(aggregate.get("id").toString());require(integer(aggregate.get("version"))>0,400,"INVALID_EVENT","Invalid aggregate version.");
            var actor=(Map<?,?>)event.get("actor");String actorType=actor.get("type").toString();require(Set.of("USER","SERVICE","SYSTEM").contains(actorType)&&!actor.get("id").toString().isBlank(),400,"INVALID_EVENT","Invalid event actor.");
            if(actorType.equals("USER"))UUID.fromString(actor.get("id").toString());
            var data=(Map<?,?>)event.get("data");UUID.fromString(data.get(type.equals("ProposalApproved")?"proposalId":"documentId").toString());
            if(type.equals("ProposalApproved")){UUID.fromString(data.get("approvalId").toString());require(integer(data.get("proposalVersion"))>0,400,"INVALID_EVENT","Invalid proposal version.");Instant.parse(data.get("approvedAt").toString());require("PROPOSAL".equals(aggregate.get("type"))&&Objects.equals(aggregate.get("id").toString(),data.get("proposalId").toString()),400,"INVALID_EVENT","Event aggregate does not match the proposal.");}
            else {require("DOCUMENT".equals(aggregate.get("type"))&&Objects.equals(aggregate.get("id").toString(),data.get("documentId").toString()),400,"INVALID_EVENT","Event aggregate does not match the document.");require(integer(data.get("accessVersion"))>0,400,"INVALID_EVENT","Invalid document access version.");Instant.parse(data.get("deletedAt").toString());}
        }catch(ApiException e){throw e;}catch(RuntimeException e){throw new ApiException(400,"INVALID_EVENT","The event does not match its contract.");}
        UUID id=UUID.fromString(event.get("eventId").toString());store.lock("incoming:"+id);
        String hash=CursorPages.hash(store.json().writeValueAsString(store.json().readTree(store.json().writeValueAsString(event))));
        var old=store.one("select payload from incoming_event where id=:id",map("id",id));
        if(old.isPresent()){require(store.json().readTree(store.json().writeValueAsString(old.get().get("payload"))).equals(store.json().readTree(store.json().writeValueAsString(event))),409,"EVENT_CONFLICT","The event ID was reused with different content.");return;}
        store.insert("incoming_event",map("id",id,"eventType",event.get("eventType"),"payload",event,"payloadHash",hash,"status","PENDING","receivedAt",Instant.now()));
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public void process(UUID id) {
        var pending=store.row("incoming_event",id,true);if(!"PENDING".equals(pending.get("status")))return;
        var event=(Map<String,Object>)pending.get("payload");var data=(Map<String,Object>)event.get("data");
        String type=event.get("eventType").toString();
        if(type.equals("ProposalApproved")) {
            UUID proposal=UUID.fromString(data.get("proposalId").toString()),approval=UUID.fromString(data.get("approvalId").toString());
            var snapshot=remote.approved(proposal,approval);
            require(((Number)snapshot.get("proposalVersion")).longValue()==((Number)data.get("proposalVersion")).longValue()
                &&Instant.parse(snapshot.get("approvedAt").toString()).equals(Instant.parse(data.get("approvedAt").toString())),409,"APPROVAL_MISMATCH","The event and approval snapshot disagree.");
            DomainEvents.fromEvent(event,()->projects.createApproved(snapshot,new CreateProject(proposal,approval,null,null,null),null));
        }else DomainEvents.fromEvent(event,()->documentDeleted(data));
        store.insert("processed_event",map("eventId",id,"consumerName","project-domain-events","eventType",type,"processedAt",Instant.now(),"result","SUCCEEDED","payloadHash",pending.get("payloadHash")));
        store.update("incoming_event",id,map("status","SUCCEEDED","lastErrorCode",null,"nextAttemptAt",null));
    }
    private static long integer(Object value) {
        require(value instanceof Integer||value instanceof Long,400,"INVALID_EVENT","Event versions must be integers.");return ((Number)value).longValue();
    }
    private void documentDeleted(Map<String,Object> data) {
        UUID document=UUID.fromString(data.get("documentId").toString());
        store.execute("insert into document_revocation(document_id,access_version,deleted_at) values(:id,:version,:at) on conflict(document_id) do update set access_version=excluded.access_version,deleted_at=excluded.deleted_at where document_revocation.access_version<excluded.access_version",map("id",document,"version",data.get("accessVersion"),"at",Instant.parse(data.get("deletedAt").toString())));
        var affected=store.query("select p.* from project p where exists(select 1 from evidence_link e where e.project_id=p.id and e.document_id=:document) or exists(select 1 from final_report r where r.project_id=p.id and r.document_id=:document) or exists(select 1 from publication r where r.project_id=p.id and r.document_id=:document) order by p.id for update of p",map("document",document));
        for(var project:affected) {
            long version=((Number)project.get("version")).longValue()+1;store.update("project",(UUID)project.get("id"),map("version",version,"updatedAt",Instant.now()));project.put("version",version);
            events.write("ProjectChanged",project,null,map("projectId",project.get("id")));
        }
    }
}
