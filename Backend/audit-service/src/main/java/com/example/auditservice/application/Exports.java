package com.example.auditservice.application;

import com.example.auditservice.api.*;
import com.example.auditservice.api.ApiModels.*;
import com.example.auditservice.domain.*;
import com.example.auditservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import static com.example.auditservice.api.ApiException.*;
import static com.example.auditservice.infrastructure.AuditStore.map;

@Service
@Transactional
public class Exports {
    private final AuditStore store;private final Queries queries;private final IdentityClient identity;
    public Exports(AuditStore store,Queries queries,IdentityClient identity){this.store=store;this.queries=queries;this.identity=identity;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> create(Caller caller,ExportInput input,String key) {
        Queries.authorize(caller);var filters=queries.filters(input.filters());String hash=AuditRules.hash(filters);
        if(key!=null) {
            require(key.matches("[A-Za-z0-9._:-]{1,200}"),400,"INVALID_IDEMPOTENCY_KEY","Invalid idempotency key.");store.lock("audit-export:"+caller.id()+":"+key);
            var existing=store.one("select * from idempotency_record where caller_id=:caller and key=:key",map("caller",caller.id(),"key",key));
            if(existing.isPresent()){require(hash.equals(existing.get().get("requestHash")),409,"IDEMPOTENCY_CONFLICT","The key was used with different filters.");return (Map<String,Object>)existing.get().get("responseBody");}
        }
        UUID id=UUID.randomUUID();store.insert("audit_export_job",map("id",id,"requestedBy",caller.id(),"filterDefinition",filters,"upperSequence",queries.ceiling(),"status","QUEUED"));
        var response=jobView(store.row("audit_export_job",id));
        if(key!=null)store.insert("idempotency_record",map("id",UUID.randomUUID(),"callerId",caller.id(),"key",key,"requestHash",hash,"responseBody",response));return response;
    }
    public Map<String,Object> get(Caller caller,UUID id){return jobView(owned(caller,id,false));}
    private Map<String,Object> owned(Caller caller,UUID id,boolean lock){Queries.authorize(caller);var row=store.row("audit_export_job",id,lock);require(caller.id().equals(row.get("requestedBy")),404,"RESOURCE_NOT_FOUND","Only the requesting auditor can access this job.");return row;}
    public Map<String,Object> authorize(Caller caller,UUID id) {
        var job=owned(caller,id,true);ready(job);byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);Instant expires=Instant.now().plusSeconds(60);
        store.insert("download_grant",map("id",UUID.randomUUID(),"jobId",id,"requestedBy",caller.id(),"tokenHash",AuditRules.sha256(token),"expiresAt",expires));
        return map("url","/api/v1/audit-export-jobs/"+id+"/content?token="+token,"expiresAt",expires);
    }
    public byte[] download(Caller caller,UUID id,String token) {
        var job=owned(caller,id,true);ready(job);
        require(token!=null&&token.matches("[A-Za-z0-9_-]{43}")&&store.one("select id from download_grant where job_id=:id and requested_by=:caller and token_hash=:hash and expires_at>now()",map("id",id,"caller",caller.id(),"hash",AuditRules.sha256(token))).isPresent(),403,"DOWNLOAD_EXPIRED","A current download authorization is required.");
        return (byte[])job.get("artifact");
    }
    @SuppressWarnings("unchecked")
    public void generate(UUID id) {
        var job=store.row("audit_export_job",id,true);if(!"QUEUED".equals(job.get("status")))return;
        Queries.authorize(identity.active((UUID)job.get("requestedBy")));store.update("audit_export_job",id,map("status","RUNNING","startedAt",Instant.now()));
        var filters=(Map<String,String>)(Map<?,?>)job.get("filterDefinition");var rows=queries.rows(filters,((Number)job.get("upperSequence")).longValue(),null,null,10001);
        require(rows.size()<=10000,422,"EXPORT_TOO_LARGE","Narrow the export filters to at most 10000 events.");
        var cells=new ArrayList<Map<String,Object>>();
        for(var row:rows){var cell=new LinkedHashMap<>(row);cell.put("metadata",store.json().writeValueAsString(row.get("metadata")));cells.add(cell);}
        byte[] bytes=Csv.write(List.of("id","sequenceNumber","occurredAt","receivedAt","actorType","actorId","action","resourceType","resourceId","projectId","outcome","correlationId","sourceService","metadata","previousHash","eventHash"),cells);
        require(bytes.length<=10*1024*1024,422,"EXPORT_TOO_LARGE","Narrow the export filters to fit 10 MiB.");Queries.authorize(identity.active((UUID)job.get("requestedBy")));
        store.update("audit_export_job",id,map("status","COMPLETED","artifact",bytes,"recordCount",rows.size(),"completedAt",Instant.now(),"expiresAt",Instant.now().plus(Duration.ofHours(24))));
    }
    public void expire(){store.execute("update audit_export_job set status='EXPIRED',artifact=null where status='COMPLETED' and expires_at<=now()",Map.of());store.execute("delete from download_grant where expires_at<=now()",Map.of());}
    private static void ready(Map<String,Object> row){require("COMPLETED".equals(row.get("status"))&&row.get("expiresAt") instanceof Instant at&&at.isAfter(Instant.now()),409,"EXPORT_NOT_READY","The export is unavailable or expired.");}
    private static Map<String,Object> jobView(Map<String,Object> row){var result=new LinkedHashMap<>(row);result.remove("artifact");if("COMPLETED".equals(row.get("status"))&&row.get("expiresAt") instanceof Instant at&&!at.isAfter(Instant.now()))result.put("status","EXPIRED");return result;}
}
