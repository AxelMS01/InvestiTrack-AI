package com.example.reportservice.application;

import com.example.reportservice.api.*;
import com.example.reportservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.example.reportservice.api.ApiException.*;
import static com.example.reportservice.infrastructure.ReportStore.map;

@Service
public class Projections {
    private final ReportStore store;
    private final RemoteServices remote;
    private static final Set<String> PROJECT_EVENTS=Set.of("ProjectCreated","ProjectChanged","ProjectMemberChanged","ActivityChanged","MilestoneChanged","DeadlineOverdue","ProjectProgressChanged","ProjectClosed","FinalReportSubmitted","PublicationRegistered");
    private static final Set<String> PROPOSAL_EVENTS=Set.of("ProposalCreated","ProposalDeleted","ProposalChanged","ProposalSubmitted","ProposalChangesRequested","ProposalRejected","ProposalApproved","EvaluatorAssigned","EvaluationSubmitted");
    public Projections(ReportStore store,RemoteServices remote){this.store=store;this.remote=remote;}
    @Transactional
    @SuppressWarnings("unchecked")
    public void accept(byte[] bytes) {
        require(bytes.length<=262144,400,"INVALID_EVENT","Maximum event size is 256 KiB.");
        Map<String,Object> e;
        try {
            e=store.json().readValue(bytes,Map.class);UUID.fromString(e.get("eventId").toString());
            String type=e.get("eventType").toString(),producer=e.get("producer").toString();
            boolean project=PROJECT_EVENTS.contains(type)&&producer.equals("project-execution-service");
            boolean proposal=PROPOSAL_EVENTS.contains(type)&&producer.equals("proposal-service");
            require(project||proposal,400,"INVALID_EVENT","Invalid event producer or type.");
            require((e.get("eventVersion") instanceof Integer||e.get("eventVersion") instanceof Long)&&((Number)e.get("eventVersion")).longValue()==1,400,"INVALID_EVENT","Unsupported event version.");Instant.parse(e.get("occurredAt").toString());
            require(e.get("correlationId").toString().matches("[A-Za-z0-9._:-]{1,100}"),400,"INVALID_EVENT","Invalid correlation ID.");
            var aggregate=(Map<String,Object>)e.get("aggregate");var data=(Map<String,Object>)e.get("data");
            UUID.fromString(aggregate.get("id").toString());
            require((aggregate.get("version") instanceof Integer||aggregate.get("version") instanceof Long)&&((Number)aggregate.get("version")).longValue()>0,400,"INVALID_EVENT","Invalid aggregate version.");
            require((project?"PROJECT":"PROPOSAL").equals(aggregate.get("type"))&&aggregate.get("id").equals(data.get(project?"projectId":"proposalId")),400,"INVALID_EVENT","Aggregate does not match event data.");
            var actor=(Map<String,Object>)e.get("actor");require(Set.of("USER","SERVICE","SYSTEM").contains(actor.get("type"))&&!actor.get("id").toString().isBlank(),400,"INVALID_EVENT","Invalid actor.");
            if("USER".equals(actor.get("type")))UUID.fromString(actor.get("id").toString());
        }catch(ApiException error){throw error;}catch(RuntimeException error){throw new ApiException(400,"INVALID_EVENT","Invalid event envelope.");}
        UUID id=UUID.fromString(e.get("eventId").toString());store.lock("event:"+id);
        var old=store.one("select payload from incoming_event where id=:id",map("id",id));
        if(old.isPresent()){require(old.get().get("payload").equals(e),409,"EVENT_CONFLICT","An event ID cannot be reused with different content.");return;}
        store.insert("incoming_event",map("id",id,"payload",e));
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public void process(UUID id) {
        var row=store.row("incoming_event",id,true);if(!"PENDING".equals(row.get("status")))return;
        var e=(Map<String,Object>)row.get("payload");var data=(Map<String,Object>)e.get("data");
        String owner="project-execution-service".equals(e.get("producer"))?"project":"proposal";
        var aggregate=(Map<String,Object>)e.get("aggregate");UUID root=UUID.fromString(aggregate.get("id").toString());
        // Signals are resolved through owner APIs, never interpreted as complete snapshots.
        var states=new ArrayList<Map<String,Object>>();states.add(remote.state(owner,owner.equals("project")?"PROJECT":"PROPOSAL",root));
        if(owner.equals("project")) {
            for(String kind:List.of("ACTIVITY","MILESTONE")) {
                Object child=data.get(kind.equals("ACTIVITY")?"activityId":"milestoneId");
                if(kind.equals(data.get("resourceType")))child=data.get("resourceId");
                if(child!=null)states.add(remote.state(owner,kind,UUID.fromString(child.toString())));
            }
        }
        // The proposal state embeds the entire assignment set at a common proposal version.
        store.lock("projection-swap");
        for(var state:states){apply(state);if("PROPOSAL".equals(state.get("aggregateType")))assignments(state);}
        store.execute("insert into processed_event(event_id) values(:id) on conflict do nothing",map("id",id));
        store.update("incoming_event",id,map("status","SUCCEEDED","lastErrorCode",null,"nextAttemptAt",null));
    }
    @SuppressWarnings("unchecked")
    private void assignments(Map<String,Object> envelope) {
        var state=(Map<String,Object>)envelope.get("state");Object raw=state.get("assignments");
        var seen=new ArrayList<String>();
        if(raw instanceof List<?> list)for(Object item:list) {
            var assignment=new LinkedHashMap<>((Map<String,Object>)item);String id=assignment.get("assignmentId").toString();seen.add(id);
            for(String field:List.of("proposalId","proposalTitle","proposalStatus","researchAreaCode","updatedAt"))assignment.put(field,state.get(field));
            apply(map("aggregateType","EVALUATOR_ASSIGNMENT","aggregateId",id,"aggregateVersion",envelope.get("aggregateVersion"),"state",assignment,"deleted",false));
        }
        var p=map("id",envelope.get("aggregateId").toString(),"version",envelope.get("aggregateVersion"));
        String exclude="";if(!seen.isEmpty()){p.put("seen",seen);exclude=" and aggregate_id::text not in (:seen)";}
        store.execute("update projection_state set deleted=true,source_version=:version,received_at=clock_timestamp() where aggregate_type='EVALUATOR_ASSIGNMENT' and state->>'proposalId'=:id and source_version<=:version"+exclude,p);
    }
    @SuppressWarnings("unchecked")
    public void apply(Map<String,Object> e) {
        validate(e);
        store.execute("""
            insert into projection_state(aggregate_type,aggregate_id,source_version,state,deleted)
            values(:type,:id,:version,cast(:state as jsonb),:deleted)
            on conflict(aggregate_type,aggregate_id) do update set source_version=excluded.source_version,
            state=excluded.state,deleted=excluded.deleted,received_at=clock_timestamp()
            where projection_state.source_version<excluded.source_version
            """,map("type",e.get("aggregateType"),"id",UUID.fromString(e.get("aggregateId").toString()),"version",e.get("aggregateVersion"),"state",e.get("state"),"deleted",Boolean.TRUE.equals(e.get("deleted"))));
    }
    public static void validate(Map<String,Object> e) {
        try {
            require(Set.of("PROJECT","ACTIVITY","MILESTONE","PROPOSAL","EVALUATOR_ASSIGNMENT").contains(e.get("aggregateType")),503,"INVALID_PROJECTION","Unsupported source aggregate.");
            UUID.fromString(e.get("aggregateId").toString());require(e.get("aggregateVersion") instanceof Number n&&n.longValue()>0&&e.get("state") instanceof Map<?,?>,503,"INVALID_PROJECTION","Invalid source state.");
        }catch(ApiException error){throw error;}catch(RuntimeException error){throw unavailable();}
    }
}
