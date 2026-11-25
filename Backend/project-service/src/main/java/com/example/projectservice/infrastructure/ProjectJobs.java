package com.example.projectservice.infrastructure;

import com.example.projectservice.api.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

@Component
@ConditionalOnProperty(name="project.jobs.enabled",havingValue="true",matchIfMissing=true)
public class ProjectJobs {
    private final ProjectStore store;
    private final ApprovalInbox inbox;
    private final RemoteServices remote;
    private final DomainEvents events;
    private final DocumentHolds holds;
    private final TransactionTemplate transaction;
    private static final long[] DELAYS={1,5,30,120,600};
    public ProjectJobs(ProjectStore store,ApprovalInbox inbox,RemoteServices remote,DomainEvents events,DocumentHolds holds,PlatformTransactionManager manager) {
        this.store=store;this.inbox=inbox;this.remote=remote;this.events=events;this.holds=holds;transaction=new TransactionTemplate(manager);
    }
    @Scheduled(initialDelay=5000,fixedDelayString="${project.inbox.poll-ms:1000}")
    public void incoming() {
        var rows=store.query("select id from incoming_event where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now()) order by received_at limit 10",Map.of());
        for(var row:rows) {
            UUID id=(UUID)row.get("id");
            try{inbox.process(id);}catch(RuntimeException error){transaction.executeWithoutResult(tx->{
                var pending=store.row("incoming_event",id,true);if(!"PENDING".equals(pending.get("status")))return;
                int attempts=((Number)pending.get("attemptCount")).intValue()+1;
                boolean permanent=error instanceof ApiException e&&e.status<500;boolean failed=permanent||attempts>DELAYS.length;
                store.update("incoming_event",id,map("status",failed?"FAILED":"PENDING","attemptCount",attempts,"lastErrorCode",error instanceof ApiException e?e.code:"PROCESSING_FAILED",
                    "nextAttemptAt",failed?null:Instant.now().plusSeconds(DELAYS[attempts-1])));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Incoming event {} is {} after attempt {}",id,failed?"FAILED":"PENDING",attempts);
            });}
        }
    }
    @Scheduled(initialDelay=10000,fixedDelayString="${project.references.poll-ms:5000}")
    public void references() {
        transaction.executeWithoutResult(tx->{
            var rows=store.query("select * from proposal_reference_job where completed_at is null and status='PENDING' and (next_attempt_at is null or next_attempt_at<=now()) order by proposal_id limit 10 for update skip locked",Map.of());
            for(var row:rows)try {
                remote.recordProject((UUID)row.get("proposalId"),(UUID)row.get("approvalId"),(UUID)row.get("projectId"));
                store.execute("update proposal_reference_job set completed_at=now(),status='COMPLETED',last_error_code=null,attempt_count=attempt_count+1 where proposal_id=:id",map("id",row.get("proposalId")));
            }catch(RuntimeException e) {
                boolean conflict=e instanceof ApiException api&&api.status==409;
                store.execute("update proposal_reference_job set attempt_count=attempt_count+1,status=:status,last_error_code=:code,next_attempt_at=:next where proposal_id=:id",map("id",row.get("proposalId"),"status",conflict?"CONFLICT":"PENDING","code",e instanceof ApiException api?api.code:"REFERENCE_UPDATE_FAILED","next",conflict?null:Instant.now().plusSeconds(300)));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Proposal reference {} requires {}",row.get("proposalId"),conflict?"conflict review":"retry");
            }
        });
    }
    @Scheduled(initialDelay=60000,fixedDelayString="${project.deadlines.poll-ms:60000}")
    public void deadlines() {
        var projects=store.query("select id from project where status in ('APPROVED','ACTIVE','DELAYED') order by id",Map.of());
        for(var candidate:projects)transaction.executeWithoutResult(tx->{
            var found=store.one("select * from project where id=:id and status in ('APPROVED','ACTIVE','DELAYED') for update skip locked",map("id",candidate.get("id")));if(found.isEmpty())return;
            var project=found.get();var overdue=store.query("select 'ACTIVITY' as resource_type,id,deadline from activity where project_id=:id and deadline<:today and status not in ('COMPLETED','CANCELLED') union all select 'MILESTONE',id,deadline from milestone where project_id=:id and deadline<:today and status not in ('COMPLETED','CANCELLED')",map("id",project.get("id"),"today",LocalDate.now(ZoneOffset.UTC)));
            boolean changed=false;
            for(var item:overdue)if(store.execute("insert into deadline_notice(resource_type,resource_id,deadline,detected_at) values(:type,:id,:deadline,now()) on conflict do nothing",map("type",item.get("resourceType"),"id",item.get("id"),"deadline",item.get("deadline")))==1) {
                String itemTable="ACTIVITY".equals(item.get("resourceType"))?"activity":"milestone";
                store.execute("update "+itemTable+" set version=version+1,updated_at=now() where id=:id",map("id",item.get("id")));
                if(!changed){long version=((Number)project.get("version")).longValue()+1;store.update("project",(UUID)project.get("id"),map("version",version,"updatedAt",Instant.now()));project.put("version",version);changed=true;}
                events.write("DeadlineOverdue",project,null,map("projectId",project.get("id"),"resourceType",item.get("resourceType"),"resourceId",item.get("id"),"dueDate",item.get("deadline"),"detectedAt",Instant.now()));
            }
        });
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void reconcileHolds(){holds.reconcile();}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void cleanExpired(){transaction.executeWithoutResult(tx->{store.execute("delete from projection_export where expires_at<now()",Map.of());store.execute("delete from idempotency_record where expires_at<now()",Map.of());});}
}
