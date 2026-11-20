package com.example.proposalservice.infrastructure;

import com.example.proposalservice.api.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.example.proposalservice.application.ProposalApplicationService.fields;

@Component
@ConditionalOnProperty(name="proposal.jobs.enabled",havingValue="true",matchIfMissing=true)
public class ProposalJobs {
    private final IntegrationStore store;
    private final ProposalInbox inbox;
    private final TransactionTemplate tx;
    private static final long[] DELAYS={1,5,30,120,600};
    public ProposalJobs(IntegrationStore store,ProposalInbox inbox,PlatformTransactionManager manager){this.store=store;this.inbox=inbox;tx=new TransactionTemplate(manager);}
    @Scheduled(initialDelay=5000,fixedDelayString="${proposal.inbox.poll-ms:1000}")
    public void incoming() {
        var candidates=store.query("select id from proposal_incoming_event where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now()) order by received_at limit 10",Map.of());
        for(var candidate:candidates) {
            UUID id=(UUID)candidate.get("id");
            try{inbox.process(id);}catch(RuntimeException failure){tx.executeWithoutResult(status->{
                var pending=store.row("proposal_incoming_event",id,true);if(!"PENDING".equals(pending.get("status")))return;
                int attempt=((Number)pending.get("attemptCount")).intValue()+1;
                boolean failed=attempt>DELAYS.length||(failure instanceof ApiException e&&e.status()<500);
                store.update("proposal_incoming_event",id,fields("status",failed?"FAILED":"PENDING","attemptCount",attempt,"lastErrorCode",failure instanceof ApiException e?e.code():"PROCESSING_FAILED","nextAttemptAt",failed?null:Instant.now().plusSeconds(DELAYS[attempt-1])));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Proposal event {} is {} after attempt {}",id,failed?"FAILED":"PENDING",attempt);
            });}
        }
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void cleanup(){tx.executeWithoutResult(status->{store.execute("delete from proposal_projection_export where expires_at<now()",Map.of());store.execute("delete from proposal_api_record where id like 'request:%' and expires_at<now()",Map.of());});}
}
