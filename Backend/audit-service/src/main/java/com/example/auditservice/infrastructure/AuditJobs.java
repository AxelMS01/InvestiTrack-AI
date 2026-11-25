package com.example.auditservice.infrastructure;

import com.example.auditservice.api.ApiException;
import com.example.auditservice.application.Exports;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.example.auditservice.infrastructure.AuditStore.map;

@Component
@ConditionalOnProperty(name="audit.jobs.enabled",havingValue="true",matchIfMissing=true)
public class AuditJobs {
    private final AuditStore store;private final Exports exports;private final TransactionTemplate tx;
    public AuditJobs(AuditStore store,Exports exports,PlatformTransactionManager manager){this.store=store;this.exports=exports;this.tx=new TransactionTemplate(manager);}
    @Scheduled(initialDelay=5000,fixedDelayString="${audit.jobs.poll-ms:1000}") public void generate() {
        for(var row:store.query("select id from audit_export_job where status='QUEUED' order by requested_at limit 5",Map.of())) {
            UUID id=(UUID)row.get("id");
            try{exports.generate(id);}catch(RuntimeException e){tx.executeWithoutResult(status->{
                var job=store.row("audit_export_job",id,true);if(!"QUEUED".equals(job.get("status")))return;
                store.update("audit_export_job",id,map("status","FAILED","failureCode",e instanceof ApiException a?a.code:"EXPORT_FAILED","completedAt",Instant.now()));
            });org.slf4j.LoggerFactory.getLogger(getClass()).warn("Audit export {} failed; details are excluded from logs",id);}
        }
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000) public void expire(){exports.expire();}
}
