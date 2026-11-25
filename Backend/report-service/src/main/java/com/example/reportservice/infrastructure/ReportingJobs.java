package com.example.reportservice.infrastructure;

import com.example.reportservice.api.ApiException;
import com.example.reportservice.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.example.reportservice.infrastructure.ReportStore.map;

@Component
@ConditionalOnProperty(name="report.jobs.enabled",havingValue="true",matchIfMissing=true)
public class ReportingJobs {
    private final ReportStore store;
    private final Projections projections;
    private final Reports reports;
    private final Rebuilds rebuilds;
    private final TransactionTemplate tx;
    private static final long[] DELAYS={1,5,30,120,600};
    public ReportingJobs(ReportStore store,Projections projections,Reports reports,Rebuilds rebuilds,PlatformTransactionManager manager){this.store=store;this.projections=projections;this.reports=reports;this.rebuilds=rebuilds;tx=new TransactionTemplate(manager);}
    @Scheduled(initialDelay=5000,fixedDelayString="${report.jobs.poll-ms:1000}")
    public void events() {
        for(var row:store.query("select id from incoming_event where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now()) order by received_at limit 10",Map.of())) {
            UUID id=(UUID)row.get("id");
            try{projections.process(id);}catch(RuntimeException error){tx.executeWithoutResult(status->{
                var pending=store.row("incoming_event",id,true);if(!"PENDING".equals(pending.get("status")))return;
                int attempts=((Number)pending.get("attemptCount")).intValue()+1;boolean failed=attempts>DELAYS.length||error instanceof ApiException e&&e.status<500;
                store.update("incoming_event",id,map("attemptCount",attempts,"status",failed?"FAILED":"PENDING","lastErrorCode",error instanceof ApiException e?e.code:"PROCESSING_FAILED","nextAttemptAt",failed?null:Instant.now().plusSeconds(DELAYS[attempts-1])));
            });}
        }
    }
    @Scheduled(initialDelay=5000,fixedDelayString="${report.jobs.poll-ms:1000}")
    public void exports() {
        for(var row:store.query("select id from report_job where status='QUEUED' order by requested_at limit 5",Map.of())) {
            UUID id=(UUID)row.get("id");
            try{reports.generate(id);}catch(RuntimeException error){tx.executeWithoutResult(status->{
                var job=store.row("report_job",id,true);if(!"QUEUED".equals(job.get("status")))return;
                store.update("report_job",id,map("status","FAILED","failureCode",error instanceof ApiException e?e.code:"GENERATION_FAILED","failureDetail","Report generation could not complete. Retry with a new job.","completedAt",Instant.now()));
            });}
        }
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void rebuilds() {for(var row:store.query("select id from rebuild_job where status='QUEUED' order by requested_at limit 1",Map.of()))rebuilds.run((UUID)row.get("id"));}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void cleanup() {tx.executeWithoutResult(status->{
        store.execute("update report_job set status='EXPIRED',artifact=null where status='COMPLETED' and expires_at<=now()",Map.of());
        store.execute("delete from download_grant where expires_at<=now()",Map.of());
        store.execute("delete from idempotency_record where expires_at<=now()",Map.of());
        // An interrupted rebuild must be explicitly restarted with a fresh owner snapshot.
        store.execute("update rebuild_job set status='FAILED',error_code='REBUILD_INTERRUPTED',completed_at=now() where status='RUNNING' and started_at<now()-interval '30 minutes'",Map.of());
        store.execute("delete from rebuild_stage where job_id in (select id from rebuild_job where status='FAILED')",Map.of());
    });}
}
