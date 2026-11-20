package com.example.proposalservice.application;

import com.example.proposalservice.api.ApiException;
import com.example.proposalservice.infrastructure.IntegrationStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.example.proposalservice.api.ApiException.require;
import static com.example.proposalservice.application.ProposalApplicationService.fields;

@Service
@Transactional(isolation=Isolation.REPEATABLE_READ)
public class ProposalProjections {
    private final IntegrationStore store;
    public ProposalProjections(IntegrationStore store){this.store=store;}
    private static final String ASSIGNMENT="""
        select 'EVALUATOR_ASSIGNMENT' as aggregate_type,a.id as aggregate_id,p.version as aggregate_version,
        jsonb_build_object('assignmentId',a.id,'proposalId',p.id,'proposalTitle',p.title,'evaluatorUserId',a.evaluator_user_id,
        'assignmentStatus',a.status,'proposalStatus',p.status,'dueDate',a.due_date,'updatedAt',greatest(a.updated_at,p.updated_at),'sourceVersion',p.version) as state
        from evaluator_assignment a join proposal p on p.id=a.proposal_id
        """;
    private static final String PROPOSAL="""
        select 'PROPOSAL' as aggregate_type,p.id as aggregate_id,p.version as aggregate_version,
        jsonb_build_object('proposalId',p.id,'proposalTitle',p.title,'proposalStatus',p.status,'ownerUserId',p.owner_user_id,
        'researchAreaCode',r.code,'submittedAt',p.submitted_at,'updatedAt',p.updated_at,'sourceVersion',p.version,
        'assignments',coalesce((select jsonb_agg(jsonb_build_object('assignmentId',a.id,'evaluatorUserId',a.evaluator_user_id,'assignmentStatus',a.status,'dueDate',a.due_date) order by a.id)
        from evaluator_assignment a where a.proposal_id=p.id),'[]'::jsonb)) as state
        from proposal p left join research_area r on r.id=p.research_area_id
        """;
    private static final String EVALUATION="""
        select 'EVALUATION' as aggregate_type,e.id as aggregate_id,p.version as aggregate_version,
        jsonb_build_object('evaluationId',e.id,'proposalId',p.id,'assignmentId',e.assignment_id,'evaluatorUserId',e.evaluator_user_id,
        'status',e.status,'submittedAt',e.submitted_at,'releasedAt',e.released_at,'sourceVersion',p.version) as state
        from evaluation e join proposal p on p.id=e.proposal_id
        """;
    public Map<String,Object> state(String type,UUID id) {
        String source=switch(type){case "PROPOSAL"->PROPOSAL;case "EVALUATOR_ASSIGNMENT"->ASSIGNMENT;case "EVALUATION"->EVALUATION;default->throw new ApiException(400,"INVALID_AGGREGATE","Unsupported aggregate type.");};
        var row=store.one("select * from ("+source+") source where aggregate_id=:id",fields("id",id));
        if(row.isPresent()){var result=row.get();result.put("deleted",false);return result;}
        if(type.equals("PROPOSAL")){var tombstone=store.one("select * from proposal_tombstone where id=:id",fields("id",id));if(tombstone.isPresent())return fields("aggregateType",type,"aggregateId",id,"aggregateVersion",tombstone.get().get("version"),"state",Map.of(),"deleted",true);}
        throw new ApiException(404,"NOT_FOUND","No aggregate or tombstone exists.");
    }
    public Map<String,Object> snapshot(String projection,String cursor,int limit) {
        require(Set.of("EVALUATIONS","PROPOSALS").contains(projection),400,"INVALID_PROJECTION","Proposal supports EVALUATIONS and PROPOSALS exports.");require(limit>=1&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        UUID id;long after=0;Instant asOf;
        if(cursor==null){id=UUID.randomUUID();asOf=Instant.now();store.insert("proposal_projection_export",fields("id",id,"projection",projection,"createdAt",asOf,"expiresAt",asOf.plus(Duration.ofMinutes(15))));
            store.execute("insert into proposal_projection_export_item(snapshot_id,ordinal,aggregate_type,aggregate_id,aggregate_version,state) select :id,row_number() over(order by aggregate_id),aggregate_type,aggregate_id,aggregate_version,state from ("+(projection.equals("EVALUATIONS")?ASSIGNMENT:PROPOSAL)+") source",fields("id",id));
        }else {
            try{require(cursor.length()<200,400,"INVALID_CURSOR","Snapshot cursor is invalid.");var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":");require(parts.length==2,400,"INVALID_CURSOR","Snapshot cursor is invalid.");id=UUID.fromString(parts[0]);after=Long.parseLong(parts[1]);require(after>=0,400,"INVALID_CURSOR","Snapshot cursor is invalid.");}
            catch(RuntimeException e){throw new ApiException(400,"INVALID_CURSOR","Snapshot cursor is invalid.");}
            var export=store.one("select * from proposal_projection_export where id=:id and projection=:projection and expires_at>now()",fields("id",id,"projection",projection)).orElseThrow(()->new ApiException(409,"SNAPSHOT_EXPIRED","The snapshot expired or belongs to another projection."));asOf=(Instant)export.get("createdAt");
        }
        var rows=store.query("select * from proposal_projection_export_item where snapshot_id=:id and ordinal>:after order by ordinal limit :limit",fields("id",id,"after",after,"limit",limit+1));
        boolean more=rows.size()>limit;var items=new ArrayList<>(rows.subList(0,Math.min(rows.size(),limit)));
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((id+":"+items.getLast().get("ordinal")).getBytes(StandardCharsets.UTF_8)):null;
        items.forEach(item->{item.remove("ordinal");item.remove("snapshotId");});return fields("snapshotId",id,"asOf",asOf,"items",items,"nextCursor",next);
    }
}
