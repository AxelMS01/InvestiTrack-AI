package com.example.auditservice;

import com.example.auditservice.api.*;
import com.example.auditservice.api.ApiModels.*;
import com.example.auditservice.application.*;
import com.example.auditservice.domain.AuditRules;
import com.example.auditservice.infrastructure.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.example.auditservice.infrastructure.AuditStore.map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"audit.messaging.enabled=false","audit.jobs.enabled=false","spring.rabbitmq.dynamic=false"})
@AutoConfigureMockMvc
class AuditServiceApplicationTests {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("AUDIT_TEST_DATABASE_URL");
        if(url==null){postgres=new PostgreSQLContainer("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
        else{if(!url.matches(".*[/]([^/?]*test[^/?]*)(\\?.*)?$"))throw new IllegalArgumentException("Use a dedicated database whose name contains test.");p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv().getOrDefault("AUDIT_TEST_DATABASE_USERNAME","audit_test"));p.add("spring.datasource.password",()->System.getenv().getOrDefault("AUDIT_TEST_DATABASE_PASSWORD",""));}
    }
    @Autowired MockMvc mvc;@Autowired AuditStore store;@Autowired Ingestion ingestion;@Autowired Queries queries;@Autowired Exports exports;@Autowired org.springframework.transaction.PlatformTransactionManager manager;AuditJobs jobs;@Autowired ObjectMapper json;
    @MockitoBean IdentityClient identity;@MockitoBean JwtDecoder decoder;
    Caller auditor,admin,outsider;UUID actor,resource;
    @BeforeEach void setup(){
        // Audit history is deliberately never cleared, even by the tests.
        store.execute("truncate audit_export_job,download_grant,idempotency_record cascade",Map.of());
        jobs=new AuditJobs(store,exports,manager);
        auditor=new Caller(UUID.randomUUID(),Set.of("AUDITOR"));admin=new Caller(UUID.randomUUID(),Set.of("ADMIN"));outsider=new Caller(UUID.randomUUID(),Set.of("RESEARCHER"));actor=UUID.randomUUID();resource=UUID.randomUUID();
        when(identity.caller(any())).thenAnswer(i->{String subject=((Jwt)i.getArgument(0)).getSubject();return subject.equals(admin.id().toString())?admin:subject.equals(outsider.id().toString())?outsider:auditor;});when(identity.active(auditor.id())).thenReturn(auditor);
    }
    AuditInput input(UUID id,Map<String,Object> metadata){return new AuditInput(id,Instant.now(),new Actor(ActorType.USER,actor.toString()),"DOCUMENT_DOWNLOADED",new Resource("DOCUMENT",resource),Outcome.SUCCESS,"test-"+actor,metadata);}
    UUID append(){UUID id=UUID.randomUUID();ingestion.append(input(id,Map.of()),"document-service");return id;}
    Map<String,String> filter(){return Map.of("actorId",actor.toString());}
    Map<String,Object> envelope(UUID id,long version){return map("eventId",id,"eventType","ProjectProgressChanged","eventVersion",1,"occurredAt",Instant.now().toString(),"producer","project-execution-service","correlationId","flow-"+actor,"actor",map("type","USER","id",actor.toString()),"aggregate",map("type","PROJECT","id",resource.toString(),"version",version),"data",map("projectId",resource.toString(),"progress",42.5));}
    RequestPostProcessor user(Caller caller){return jwt().jwt(j->j.subject(caller.id().toString()).audience(List.of("research-platform-api")));}
    RequestPostProcessor workload(String client){return jwt().jwt(j->j.subject(client).audience(List.of("audit-service")).claim("client_id",client)).authorities(new SimpleGrantedAuthority("SCOPE_audit:append"));}
    @Test void restIngestionDerivesProducerAndDeduplicatesContradictions() throws Exception {
        UUID id=UUID.randomUUID();AuditInput request=input(id,Map.of("projectId",UUID.randomUUID().toString()));String body=json.writeValueAsString(request);
        mvc.perform(post("/internal/v1/audit-events").with(workload("document-service")).contentType("application/json").content(body)).andExpect(status().isCreated());
        mvc.perform(post("/internal/v1/audit-events").with(workload("document-service")).contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(store.row("audit_event",id).get("sourceService")).isEqualTo("document-service");
        AuditInput changed=new AuditInput(id,request.occurredAt(),request.actor(),"DOCUMENT_DELETED",request.resource(),request.outcome(),request.correlationId(),request.metadata());
        mvc.perform(post("/internal/v1/audit-events").with(workload("document-service")).contentType("application/json").content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        var spoof=map("eventId",UUID.randomUUID(),"occurredAt",Instant.now(),"actor",request.actor(),"action",request.action(),"resource",request.resource(),"outcome","SUCCESS","correlationId","flow","producer","identity-service");
        mvc.perform(post("/internal/v1/audit-events").with(workload("document-service")).contentType("application/json").content(json.writeValueAsString(spoof))).andExpect(status().isBadRequest());
        assertThat(store.query("select * from processed_event where event_id=:id",map("id",id))).hasSize(1);
    }
    @Test void metadataIsRedactedBeforeItReachesHistoryAndInbox(){
        UUID id=UUID.randomUUID(),project=UUID.randomUUID();var metadata=map("projectId",project,"documentVersion",2,"token","secret-token","signedUrl","https://example/private?secret","text","confidential document","question","private question","nested",Map.of("password","hidden"));
        ingestion.append(input(id,metadata),"document-service");var row=store.row("audit_event",id);assertThat((Map<?,?>)row.get("metadata")).hasSize(2);assertThat(row.get("projectId")).isEqualTo(project);
        assertThat(json.writeValueAsString(row)).doesNotContain("secret-token","https://example","confidential document","private question","hidden");assertThat(Ingestion.validHash(row)).isTrue();
        assertThat(queries.get(auditor,id)).doesNotContainKey("payloadHash");
    }
    @Test void domainEventsAreIdempotentAndKeepOutOfOrderAndSameVersionFacts(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();var first=envelope(a,7);byte[] bytes=json.writeValueAsBytes(first);
        assertThat(ingestion.consume(bytes,"project.ProjectProgressChanged.v1").created()).isTrue();assertThat(ingestion.consume(bytes,"project.ProjectProgressChanged.v1").created()).isFalse();
        ingestion.consume(json.writeValueAsBytes(envelope(b,7)),"project.ProjectProgressChanged.v1");ingestion.consume(json.writeValueAsBytes(envelope(c,2)),"project.ProjectProgressChanged.v1");
        assertThat((List<?>)queries.search(auditor,filter()).get("items")).hasSize(3);
        first.put("data",map("projectId",resource.toString(),"progress",80));assertThatThrownBy(()->ingestion.consume(json.writeValueAsBytes(first),"project.ProjectProgressChanged.v1")).isInstanceOf(ApiException.class).hasMessageContaining("different content");
    }
    @Test void fallbackProjectScopeIsDerivedAndContradictoryScopeIsRejected(){
        UUID id=UUID.randomUUID();var event=new AuditInput(id,Instant.now(),new Actor(ActorType.USER,actor.toString()),"PROJECT_CHANGED",new Resource("PROJECT",resource),Outcome.SUCCESS,"project-1",Map.of());
        ingestion.append(event,"project-execution-service");assertThat(store.row("audit_event",id).get("projectId")).isEqualTo(resource);
        var bad=new AuditInput(UUID.randomUUID(),event.occurredAt(),event.actor(),event.action(),event.resource(),event.outcome(),event.correlationId(),Map.of("projectId",UUID.randomUUID().toString()));
        assertThatThrownBy(()->ingestion.append(bad,"project-execution-service")).isInstanceOf(ApiException.class);
    }
    @Test void malformedUnsupportedAndSpoofedEventsDoNotAppendAnything(){
        long ceiling=queries.ceiling();var e=envelope(UUID.randomUUID(),1);e.put("producer","document-service");assertThatThrownBy(()->ingestion.consume(json.writeValueAsBytes(e),"project.ProjectProgressChanged.v1")).isInstanceOf(ApiException.class);
        e.put("producer","project-execution-service");e.put("eventVersion",2);assertThatThrownBy(()->ingestion.consume(json.writeValueAsBytes(e),"project.ProjectProgressChanged.v1")).isInstanceOf(ApiException.class);
        e.put("eventVersion",1);((Map<String,Object>)e.get("data")).put("projectId",UUID.randomUUID().toString());assertThatThrownBy(()->ingestion.consume(json.writeValueAsBytes(e),"project.ProjectProgressChanged.v1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->ingestion.consume(new byte[256*1024+1],"project.ProjectProgressChanged.v1")).isInstanceOf(ApiException.class);assertThat(queries.ceiling()).isEqualTo(ceiling);
    }
    @Test void databaseRejectsUpdateDeleteAndTruncateAndChainDetectsChanges(){
        UUID a=append(),b=append();var first=store.row("audit_event",a);var second=store.row("audit_event",b);
        assertThat(second.get("previousHash")).isEqualTo(first.get("eventHash"));assertThat(Ingestion.validHash(first)).isTrue();assertThat(Ingestion.validHash(second)).isTrue();
        assertThatThrownBy(()->store.execute("update audit_event set action='CHANGED' where id=:id",map("id",a))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->store.execute("delete from audit_event where id=:id",map("id",a))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->store.execute("truncate audit_event cascade",Map.of())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->store.execute("delete from processed_event where event_id=:id",map("id",a))).isInstanceOf(DataAccessException.class);
        first.put("action","CHANGED");assertThat(Ingestion.validHash(first)).isFalse();assertThat(store.row("audit_event",a).get("action")).isEqualTo("DOCUMENT_DOWNLOADED");
    }
    @Test void concurrentDuplicatesAppendExactlyOnceAndPreserveTheChain() throws Exception {
        UUID id=UUID.randomUUID();var request=input(id,Map.of());
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->ingestion.append(request,"document-service"));var b=executor.submit(()->ingestion.append(request,"document-service"));
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS).created(),b.get(10,java.util.concurrent.TimeUnit.SECONDS).created())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(Ingestion.validHash(store.row("audit_event",id))).isTrue();
    }
    @Test void searchFiltersAndCursorsAreStableAcrossLateEventsAndBoundToTheCaller(){
        append();append();var f=new HashMap<>(filter());f.put("limit","1");var first=queries.search(auditor,f);var page=(Map<?,?>)first.get("page");assertThat(page.get("hasMore")).isEqualTo(true);
        ingestion.append(new AuditInput(UUID.randomUUID(),Instant.now().minusSeconds(3600),new Actor(ActorType.USER,actor.toString()),"DOCUMENT_DOWNLOADED",new Resource("DOCUMENT",resource),Outcome.SUCCESS,"late",Map.of()),"document-service");
        f.put("cursor",page.get("nextCursor").toString());var second=queries.search(auditor,f);assertThat(((Map<?,?>)second.get("page")).get("hasMore")).isEqualTo(false);
        assertThatThrownBy(()->queries.search(admin,f)).isInstanceOf(ApiException.class);f.put("action","OTHER");assertThatThrownBy(()->queries.search(auditor,f)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->queries.search(auditor,Map.of("from","2026-01-02","to","2026-01-01"))).isInstanceOf(ApiException.class);
    }
    @Test void publicAccessRequiresCurrentAuditorRoleAndNoPublicWriteExists() throws Exception {
        UUID id=append();mvc.perform(get("/api/v1/audit-events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit-events/"+id).with(user(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events/"+id).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.actor.id").value(actor.toString()));
        mvc.perform(post("/api/v1/audit-events").with(user(auditor))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/internal/v1/audit-events").with(user(admin))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/audit-events").with(workload("intruder"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").with(workload("document-service"))).andExpect(status().isForbidden());
        doThrow(ApiException.unavailable()).when(identity).caller(any());mvc.perform(get("/api/v1/audit-events/"+id).with(user(auditor))).andExpect(status().isServiceUnavailable());
    }
    @Test void exportJobsUseFrozenBoundariesArePrivateAndRequireCurrentPrivilege(){
        UUID first=append();var input=new ExportInput(filter());var created=exports.create(auditor,input,"export-1");UUID id=(UUID)created.get("id");append();
        assertThat(exports.create(auditor,input,"export-1").get("id").toString()).isEqualTo(id.toString());assertThatThrownBy(()->exports.create(auditor,new ExportInput(Map.of()),"export-1")).isInstanceOf(ApiException.class);
        exports.generate(id);assertThat(exports.get(auditor,id)).containsEntry("recordCount",1).doesNotContainKey("artifact");assertThatThrownBy(()->exports.get(admin,id)).isInstanceOf(ApiException.class);
        var grant=exports.authorize(auditor,id);String token=grant.get("url").toString().split("token=")[1];String csv=new String(exports.download(auditor,id,token),java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).contains(first.toString());
        assertThatThrownBy(()->exports.download(new Caller(auditor.id(),Set.of("RESEARCHER")),id,token)).isInstanceOf(ApiException.class);
        store.update("audit_export_job",id,map("expiresAt",Instant.now().minusSeconds(1)));assertThatThrownBy(()->exports.download(auditor,id,token)).isInstanceOf(ApiException.class);exports.expire();assertThat(store.row("audit_export_job",id).get("artifact")).isNull();
    }
    @Test void workersFailDurablyOnRevokedRolesAndDoNotLeavePartialArtifacts(){
        append();UUID id=(UUID)exports.create(auditor,new ExportInput(filter()),null).get("id");when(identity.active(auditor.id())).thenReturn(new Caller(auditor.id(),Set.of("RESEARCHER")));jobs.generate();
        assertThat(exports.get(auditor,id)).containsEntry("status","FAILED").containsEntry("failureCode","AUDITOR_REQUIRED");assertThat(store.row("audit_export_job",id).get("artifact")).isNull();
    }
    @Test void internalHttpInputIsBoundedAndCannotAttestForeignResources() throws Exception {
        String huge="{\"metadata\":{\"text\":\""+"x".repeat(256*1024)+"\"}}";
        mvc.perform(post("/internal/v1/audit-events").with(workload("document-service")).contentType("application/json").content(huge)).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/internal/v1/audit-events").with(workload("identity-service")).contentType("application/json").content(json.writeValueAsString(input(UUID.randomUUID(),Map.of())))).andExpect(status().isForbidden());
    }
}
