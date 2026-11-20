package com.example.projectservice.infrastructure;

import com.example.projectservice.api.ApiModels.Caller;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.Instant;
import java.util.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

/** Durable reservation intents allow recovery even if the process dies between the HTTP call and DB commit. */
@Component
public class DocumentHolds {
    private final ProjectStore store;
    private final RemoteServices remote;
    private final TransactionTemplate separate;
    public DocumentHolds(ProjectStore store,RemoteServices remote,PlatformTransactionManager manager) {
        this.store=store;this.remote=remote;separate=new TransactionTemplate(manager);separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public UUID reserve(UUID project,UUID document,int version,Caller caller) {
        var id=UUID.randomUUID();var now=Instant.now();
        separate.executeWithoutResult(tx->store.insert("document_hold_intent",map("id",id,"projectId",project,"documentId",document,"documentVersion",version,"status","INTENT","createdAt",now,"updatedAt",now)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) {
                    separate.executeWithoutResult(tx->store.update("document_hold_intent",id,map("status","RELEASE_PENDING","updatedAt",Instant.now())));
                    // The retry worker releases this intent using the Project workload's own credentials.
                }
            }
        });
        remote.hold(project,document,version,id.toString(),caller);
        separate.executeWithoutResult(tx->store.update("document_hold_intent",id,map("status","HELD","updatedAt",Instant.now())));
        return id;
    }
    public void reconcile() {
        var candidates=store.query("select id,project_id from document_hold_intent where status in ('INTENT','HELD','RELEASE_PENDING') and updated_at<now()-interval '5 minutes' order by created_at limit 20",Map.of());
        for(var intent:candidates) {
            try {separate.executeWithoutResult(tx->{
                var project=store.one("select id,status from project where id=:id for update skip locked",map("id",intent.get("projectId")));
                if(project.isEmpty())return;
                var current=store.row("document_hold_intent",(UUID)intent.get("id"),true);
                if(Set.of("CONFIRMED","RELEASED").contains(current.get("status")))return;
                boolean used=store.one("select id from final_report where project_id=:project and hold_id=:hold",map("project",intent.get("projectId"),"hold",intent.get("id"))).isPresent()
                    &&"CLOSED".equals(project.get().get("status"));
                if(used)store.update("document_hold_intent",(UUID)intent.get("id"),map("status","CONFIRMED","updatedAt",Instant.now()));
                else {remote.release((UUID)current.get("documentId"),(UUID)current.get("id"));store.update("document_hold_intent",(UUID)intent.get("id"),map("status","RELEASED","updatedAt",Instant.now()));}
            });}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document hold {} remains pending reconciliation",intent.get("id"));}
        }
    }
}
