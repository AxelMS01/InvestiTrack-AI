package com.example.reportservice.application;

import com.example.reportservice.api.*;
import com.example.reportservice.api.ApiModels.Rebuild;
import com.example.reportservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.example.reportservice.api.ApiException.*;
import static com.example.reportservice.infrastructure.ReportStore.map;

@Service
public class Rebuilds {
    private final ReportStore store;
    private final RemoteServices remote;
    private final TransactionTemplate tx;
    public Rebuilds(ReportStore store,RemoteServices remote,PlatformTransactionManager manager){this.store=store;this.remote=remote;tx=new TransactionTemplate(manager);}
    @Transactional
    public Map<String,Object> create(Rebuild input) {
        require(Set.of("PROJECTS","DEADLINES","EVALUATIONS").contains(input.projection()),400,"INVALID_PROJECTION","Unknown projection.");
        require(Set.of("projectIds").containsAll(input.scope().keySet()),400,"INVALID_SCOPE","Only projectIds scope is supported.");
        Reports.projectIds(input.scope());
        require(!input.projection().equals("EVALUATIONS")||input.scope().isEmpty(),400,"INVALID_SCOPE","Evaluation rebuilds require a full scope; assignments belong to proposals.");
        UUID id=UUID.randomUUID();store.insert("rebuild_job",map("id",id,"projection",input.projection(),"scope",input.scope(),"reason",input.reason()));return get(id);
    }
    public Map<String,Object> get(UUID id){var result=store.row("rebuild_job",id);result.put("jobId",result.remove("id"));return result;}
    @SuppressWarnings("unchecked")
    public void run(UUID id) {
        var job=tx.execute(status->{
            var candidate=store.row("rebuild_job",id,true);if(!"QUEUED".equals(candidate.get("status")))return null;
            store.update("rebuild_job",id,map("status","RUNNING","startedAt",Instant.now()));return candidate;
        });
        if(job==null)return;
        try {
            String projection=job.get("projection").toString(),owner=projection.equals("EVALUATIONS")?"proposal":"project";
            var scope=Reports.projectIds((Map<String,Object>)job.get("scope"));
            String cursor=null,snapshot=null;int count=0;var cursors=new HashSet<String>();
            do {
                var page=remote.snapshot(owner,projection,cursor);
                require(page.get("items") instanceof List<?> &&page.get("snapshotId")!=null&&page.get("asOf")!=null,503,"INVALID_SNAPSHOT","Invalid snapshot response.");
                Instant.parse(page.get("asOf").toString());String current=page.get("snapshotId").toString();
                require(snapshot==null||snapshot.equals(current),503,"INVALID_SNAPSHOT","Snapshot ID changed between pages.");snapshot=current;
                var items=(List<Map<String,Object>>)page.get("items");require(items.size()<=100,503,"INVALID_SNAPSHOT","Snapshot page exceeds 100 items.");
                if(projection.equals("EVALUATIONS")) {
                    var proposals=new HashMap<UUID,Map<String,Object>>();
                    var enriched=new ArrayList<Map<String,Object>>();
                    for(var item:items) {
                        Projections.validate(item);
                        var state=(Map<String,Object>)item.get("state");UUID proposalId=UUID.fromString(state.get("proposalId").toString());
                        var proposal=proposals.computeIfAbsent(proposalId,key->remote.state("proposal","PROPOSAL",key));
                        Projections.validate(proposal);
                        require(((Number)proposal.get("aggregateVersion")).longValue()>=((Number)item.get("aggregateVersion")).longValue(),503,"INVALID_SNAPSHOT","Owner state is older than its snapshot.");
                        var root=(Map<String,Object>)proposal.get("state");Map<String,Object> assignment=null;
                        if(root.get("assignments") instanceof List<?> assignments)for(Object raw:assignments) {
                            var candidate=(Map<String,Object>)raw;
                            if(candidate.get("assignmentId").toString().equals(item.get("aggregateId").toString()))assignment=new LinkedHashMap<>(candidate);
                        }
                        boolean deleted=assignment==null;
                        if(deleted)assignment=map("proposalId",proposalId);
                        else for(String field:List.of("proposalId","proposalTitle","proposalStatus","researchAreaCode","updatedAt"))assignment.put(field,root.get(field));
                        enriched.add(map("aggregateType","EVALUATOR_ASSIGNMENT","aggregateId",item.get("aggregateId"),"aggregateVersion",proposal.get("aggregateVersion"),"state",assignment,"deleted",deleted));
                    }
                    items=enriched;
                }
                var stagedItems=items;
                tx.executeWithoutResult(status->{for(var item:stagedItems) {
                    Projections.validate(item);var state=(Map<String,Object>)item.get("state");
                    require(types(projection).contains(item.get("aggregateType")),503,"INVALID_SNAPSHOT","Unexpected aggregate type.");
                    if(scope!=null&&!scope.contains(UUID.fromString(state.get("projectId").toString())))continue;
                    store.execute("""
                        insert into rebuild_stage(job_id,aggregate_type,aggregate_id,source_version,state,deleted)
                        values(:job,:type,:id,:version,cast(:state as jsonb),:deleted)
                        on conflict(job_id,aggregate_type,aggregate_id) do update set
                        source_version=excluded.source_version,state=excluded.state,deleted=excluded.deleted where rebuild_stage.source_version<excluded.source_version
                        """,map("job",id,"type",item.get("aggregateType"),"id",UUID.fromString(item.get("aggregateId").toString()),"version",item.get("aggregateVersion"),"state",state,"deleted",Boolean.TRUE.equals(item.get("deleted"))));
                }});
                count+=items.size();require(count<=1000000,422,"REBUILD_TOO_LARGE","Rebuild exceeded its bounded size.");
                cursor=(String)page.get("nextCursor");require(cursor==null||cursors.add(cursor),503,"INVALID_SNAPSHOT","Source repeated a cursor.");
            }while(cursor!=null);
            int total=count;
            tx.executeWithoutResult(status->{
                store.lock("projection-swap");var p=map("job",id,"types",types(projection));String restriction="";
                if(scope!=null){p.put("ids",scope.stream().map(UUID::toString).toList());restriction=" and state->>'projectId' in (:ids)";}
                // Reconcile concurrent events and tombstones before an atomic replacement.
                store.execute("""
                    insert into rebuild_stage(job_id,aggregate_type,aggregate_id,source_version,state,deleted)
                    select :job,aggregate_type,aggregate_id,source_version,state,deleted from projection_state live
                    where aggregate_type in (:types)
                    """+restriction+"""
                    and (received_at >= (select started_at from rebuild_job where id=:job)
                      or exists(select 1 from rebuild_stage s where s.job_id=:job and s.aggregate_type=live.aggregate_type and s.aggregate_id=live.aggregate_id and s.source_version<live.source_version))
                    on conflict(job_id,aggregate_type,aggregate_id) do update set source_version=excluded.source_version,state=excluded.state,deleted=excluded.deleted
                    where rebuild_stage.source_version<excluded.source_version
                    """,p);
                store.execute("delete from projection_state where aggregate_type in (:types)"+restriction,p);
                store.execute("insert into projection_state(aggregate_type,aggregate_id,source_version,state,deleted) select aggregate_type,aggregate_id,source_version,state,deleted from rebuild_stage where job_id=:job",p);
                store.update("rebuild_job",id,map("status","SUCCEEDED","processedCount",total,"completedAt",Instant.now()));
                store.execute("delete from rebuild_stage where job_id=:job",p);
            });
        }catch(RuntimeException error){tx.executeWithoutResult(status->{store.update("rebuild_job",id,map("status","FAILED","errorCode",error instanceof ApiException e?e.code:"REBUILD_FAILED","completedAt",Instant.now()));store.execute("delete from rebuild_stage where job_id=:job",map("job",id));});}
    }
    private List<String> types(String projection){return switch(projection){case "PROJECTS"->List.of("PROJECT");case "DEADLINES"->List.of("ACTIVITY","MILESTONE");default->List.of("EVALUATOR_ASSIGNMENT");};}
}
