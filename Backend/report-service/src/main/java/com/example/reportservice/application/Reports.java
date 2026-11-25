package com.example.reportservice.application;

import com.example.reportservice.api.*;
import com.example.reportservice.api.ApiModels.*;
import com.example.reportservice.domain.Csv;
import com.example.reportservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.example.reportservice.api.ApiException.*;
import static com.example.reportservice.infrastructure.ReportStore.map;

@Service
@Transactional
public class Reports {
    private final ReportStore store;
    private final Queries queries;
    private final RemoteServices remote;
    public Reports(ReportStore store,Queries queries,RemoteServices remote){this.store=store;this.queries=queries;this.remote=remote;}
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> definitions(Caller caller) {
        var rows=store.query("select * from report_definition where active order by code",Map.of());
        rows.removeIf(d->((List<?>)d.get("allowedRoles")).stream().noneMatch(r->caller.roles().contains(r.toString())));
        return rows;
    }
    public Map<String,Object> definition(Caller caller,String code) {
        return definitions(caller).stream().filter(d->code.equals(d.get("code"))).findFirst().orElseThrow(()->new ApiException(404,"REPORT_NOT_FOUND","The report definition is absent or not visible."));
    }
    public Map<String,Object> create(Caller caller,CreateReport input,String key) {
        definition(caller,input.reportCode());require("CSV".equals(input.format()),422,"UNSUPPORTED_FORMAT","This definition supports CSV.");
        validate(input.reportCode(),input.parameters());
        if(key!=null) {
            require(key.matches("[A-Za-z0-9._:-]{1,200}"),400,"INVALID_IDEMPOTENCY_KEY","Invalid idempotency key.");store.lock("report:"+caller.id()+":"+key);
            store.execute("delete from idempotency_record where expires_at<=now()",Map.of());
            var old=store.one("select * from idempotency_record where caller_id=:caller and operation='CREATE_REPORT' and key=:key",map("caller",caller.id(),"key",key));
            if(old.isPresent()) {
                require(old.get().get("payload").equals(store.json().readValue(store.json().writeValueAsString(input),Map.class)),409,"IDEMPOTENCY_CONFLICT","The key was already used with another request.");
                @SuppressWarnings("unchecked") var response=(Map<String,Object>)old.get().get("responseBody");return response;
            }
        }
        // Persist the authorized scope, not a credential. A worker can only narrow it later.
        var scope=queries.scope(caller,"PROJECT");var requested=projectIds(input.parameters());
        if(requested!=null){require(scope.containsAll(requested),404,"RESOURCE_NOT_FOUND","One or more projects are absent or not visible.");scope.retainAll(requested);}
        UUID id=UUID.randomUUID();store.insert("report_job",map("id",id,"reportCode",input.reportCode(),"requestedBy",caller.id(),"parameters",input.parameters(),"format",input.format(),"status","QUEUED","resourceIds",scope));
        var response=publicJob(store.row("report_job",id));
        if(key!=null)store.insert("idempotency_record",map("id",UUID.randomUUID(),"callerId",caller.id(),"operation","CREATE_REPORT","key",key,"payload",input,"responseBody",response,"expiresAt",Instant.now().plus(Duration.ofDays(1))));
        return response;
    }
    public static Set<UUID> projectIds(Map<String,Object> parameters) {
        if(!parameters.containsKey("projectIds"))return null;
        require(parameters.get("projectIds") instanceof List<?>,400,"INVALID_PARAMETERS","projectIds must be an array.");
        var raw=(List<?>)parameters.get("projectIds");require(!raw.isEmpty()&&raw.size()<=100,400,"INVALID_PARAMETERS","projectIds must contain 1 to 100 unique UUIDs.");
        var ids=new HashSet<UUID>();for(var id:raw)require(ids.add(UUID.fromString(id.toString())),400,"INVALID_PARAMETERS","Duplicate project ID.");return ids;
    }
    private void validate(String code,Map<String,Object> p) {
        Set<String> allowed=code.equals("PROJECT_PORTFOLIO_STATUS")?Set.of("projectIds","researchArea","status","coordinatorId"):Set.of("projectIds","state","type","from","to");
        require(allowed.containsAll(p.keySet())&&p.values().stream().noneMatch(Objects::isNull),400,"INVALID_PARAMETERS","Unsupported or null report parameter. Reports show current data; historical asOfDate is not supported.");
        projectIds(p);
        for(var e:p.entrySet())if(!Set.of("projectIds","status").contains(e.getKey()))require(e.getValue() instanceof String,400,"INVALID_PARAMETERS","Report parameters must match the definition schema.");
        if(p.containsKey("status")){require(p.get("status") instanceof List<?> list&&!list.isEmpty()&&list.size()<=20&&list.stream().allMatch(v->v instanceof String),400,"INVALID_PARAMETERS","status must be a nonempty array of strings.");}
        for(String date:List.of("from","to"))if(p.containsKey(date))LocalDate.parse(p.get(date).toString());
        if(p.containsKey("from")&&p.containsKey("to"))require(p.get("from").toString().compareTo(p.get("to").toString())<=0,400,"INVALID_PARAMETERS","from must not exceed to.");
        if(p.containsKey("coordinatorId"))UUID.fromString(p.get("coordinatorId").toString());
        if(p.containsKey("state"))require(Set.of("FUTURE","UPCOMING","OVERDUE","COMPLETED").contains(p.get("state")),400,"INVALID_PARAMETERS","Invalid deadline state.");
        if(p.containsKey("type"))require(Set.of("ACTIVITY","MILESTONE").contains(p.get("type")),400,"INVALID_PARAMETERS","Invalid resource type.");
    }
    public List<Map<String,Object>> list(Caller caller) {
        // Owner listing is deliberately the default for administrators too.
        return store.query("select id,report_code,requested_by,parameters,format,status,as_of,requested_at,started_at,completed_at,expires_at,failure_code,failure_detail from report_job where requested_by=:caller order by requested_at desc,id limit 10001",map("caller",caller.id())).stream().map(this::publicJob).toList();
    }
    private Map<String,Object> owned(Caller caller,UUID id,boolean lock) {
        var row=store.row("report_job",id,lock);require(caller.id().equals(row.get("requestedBy"))||caller.has("ADMIN"),404,"RESOURCE_NOT_FOUND","The report job is absent or not visible.");return row;
    }
    public Map<String,Object> get(Caller caller,UUID id){return publicJob(owned(caller,id,false));}
    public void cancel(Caller caller,UUID id) {
        owned(caller,id,true);store.update("report_job",id,map("status","CANCELLED","artifact",null,"completedAt",Instant.now()));
        store.execute("delete from download_grant where job_id=:id",map("id",id));
    }
    private Map<String,Object> publicJob(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);result.remove("artifact");result.remove("resourceIds");return result;
    }
    @SuppressWarnings("unchecked")
    private Set<UUID> scope(Map<String,Object> job) {var ids=new HashSet<UUID>();for(Object id:(List<Object>)job.get("resourceIds"))ids.add(UUID.fromString(id.toString()));return ids;}
    private void revalidate(Caller caller,Map<String,Object> job) {
        definition(caller,job.get("reportCode").toString());var ids=scope(job);
        require(remote.allowed(caller,"PROJECT",ids).containsAll(ids),403,"REPORT_ACCESS_REVOKED","Current project access no longer permits this report. Generate a new report.");
    }
    public Map<String,Object> authorize(Caller caller,UUID id) {
        var job=owned(caller,id,true);ready(job);revalidate(caller,job);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires=Instant.now().plusSeconds(60);
        store.insert("download_grant",map("id",UUID.randomUUID(),"jobId",id,"requestedBy",caller.id(),"tokenHash",hash(token),"expiresAt",expires));
        return map("url","/api/v1/report-jobs/"+id+"/content?token="+token,"expiresAt",expires);
    }
    public byte[] download(Caller caller,UUID id,String token) {
        var job=owned(caller,id,true);ready(job);
        require(store.one("select id from download_grant where job_id=:id and requested_by=:caller and token_hash=:hash and expires_at>now()",map("id",id,"caller",caller.id(),"hash",hash(token))).isPresent(),403,"DOWNLOAD_EXPIRED","A current download authorization is required.");
        revalidate(caller,job);return (byte[])job.get("artifact");
    }
    private void ready(Map<String,Object> job) {require("COMPLETED".equals(job.get("status"))&&job.get("expiresAt") instanceof Instant expiry&&expiry.isAfter(Instant.now()),409,"REPORT_NOT_READY","The report is unavailable or expired.");}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked")
    public void generate(UUID id) {
        var job=store.row("report_job",id,true);if(!"QUEUED".equals(job.get("status")))return;
        store.update("report_job",id,map("status","RUNNING","startedAt",Instant.now()));
        Caller owner=remote.active((UUID)job.get("requestedBy"));revalidate(owner,job);
        var p=(Map<String,Object>)job.get("parameters");var filters=new HashMap<String,String>();p.forEach((k,v)->{if(v instanceof String s)filters.put(k,s);});
        var rows=queries.rows(job.get("reportCode").equals("PROJECT_PORTFOLIO_STATUS")?"PROJECTS":"DEADLINES",scope(job),filters);
        if(p.get("status") instanceof List<?> statuses)rows=new ArrayList<>(rows.stream().filter(r->statuses.contains(r.get("status"))).toList());
        var ids=new HashSet<UUID>();rows.forEach(r->ids.add(UUID.fromString(r.get("projectId").toString())));
        List<String> columns=job.get("reportCode").equals("PROJECT_PORTFOLIO_STATUS")?List.of("projectId","projectNumber","title","status","progressPercent","researchAreaCode","plannedEndDate"):List.of("projectId","projectTitle","resourceType","resourceId","resourceName","deadline","deadlineState","status");
        byte[] bytes=Csv.write(columns,rows);
        require(bytes.length<=10*1024*1024,422,"REPORT_TOO_LARGE","The generated report exceeds 10 MiB; narrow the filters.");
        revalidate(owner,job);
        store.update("report_job",id,map("status","COMPLETED","artifact",bytes,"resourceIds",ids,"asOf",queries.asOf(),"completedAt",Instant.now(),"expiresAt",Instant.now().plus(Duration.ofHours(24))));
    }
}
