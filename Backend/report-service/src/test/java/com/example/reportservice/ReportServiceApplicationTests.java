package com.example.reportservice;

import com.example.reportservice.api.*;
import com.example.reportservice.api.ApiModels.*;
import com.example.reportservice.application.*;
import com.example.reportservice.infrastructure.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.example.reportservice.infrastructure.ReportStore.map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"report.messaging.enabled=false","report.jobs.enabled=false","spring.rabbitmq.dynamic=false"})
@AutoConfigureMockMvc
class ReportServiceApplicationTests {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("REPORT_TEST_DATABASE_URL");
        if(url==null){postgres=new PostgreSQLContainer("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
        else {if(!url.matches(".*[/]([^/?]*test[^/?]*)(\\?.*)?$"))throw new IllegalArgumentException("Use a dedicated database whose name contains test.");p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv().getOrDefault("REPORT_TEST_DATABASE_USERNAME","report_test"));p.add("spring.datasource.password",()->System.getenv().getOrDefault("REPORT_TEST_DATABASE_PASSWORD","report_test"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ReportStore store;
    @Autowired Queries queries;
    @Autowired Projections projections;
    @Autowired Reports reports;
    @Autowired Rebuilds rebuilds;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @MockitoBean RemoteServices remote;
    @MockitoBean JwtDecoder decoder;
    Caller researcher,coordinator,outsider;
    Set<UUID> visible;
    @BeforeEach void setup() {
        store.execute("truncate projection_state,incoming_event,report_job,rebuild_job,idempotency_record cascade",Map.of());
        researcher=new Caller(UUID.randomUUID(),Set.of("RESEARCHER"));coordinator=new Caller(UUID.randomUUID(),Set.of("COORDINATOR"));outsider=new Caller(UUID.randomUUID(),Set.of("RESEARCHER"));visible=new HashSet<>();
        when(remote.caller(any())).thenAnswer(i->{String s=((Jwt)i.getArgument(0)).getSubject();return s.equals(coordinator.id().toString())?coordinator:s.equals(outsider.id().toString())?outsider:researcher;});
        when(remote.active(researcher.id())).thenReturn(researcher);
        when(remote.allowed(any(),anyString(),anyCollection())).thenAnswer(i->{Caller c=i.getArgument(0);Collection<UUID> ids=i.getArgument(2);var result=new HashSet<>(ids);result.retainAll(c.id().equals(outsider.id())?Set.of():visible);return result;});
    }
    RequestPostProcessor as(Caller caller){return jwt().jwt(j->j.subject(caller.id().toString()).audience(List.of("research-platform-api")));}
    RequestPostProcessor workload(String client,String audience){return jwt().jwt(j->j.subject(client).audience(List.of(audience)).claim("client_id",client)).authorities(new SimpleGrantedAuthority("SCOPE_reporting:rebuild"));}
    void tx(Runnable action){new TransactionTemplate(manager).executeWithoutResult(s->action.run());}
    Map<String,Object> envelope(String type,UUID id,long version,Map<String,Object> state,boolean deleted){return map("aggregateType",type,"aggregateId",id.toString(),"aggregateVersion",version,"state",state,"deleted",deleted);}
    UUID project(String title) {
        UUID id=UUID.randomUUID();visible.add(id);tx(()->projections.apply(envelope("PROJECT",id,1,map("projectId",id,"projectNumber","P-1","title",title,"status","ACTIVE","progressPercent",25,"researchAreaCode","ENGINEERING","principalResearcherId",researcher.id(),"coordinatorUserId",coordinator.id(),"activeMemberIds",List.of(researcher.id()),"plannedEndDate",LocalDate.now(ZoneOffset.UTC).plusDays(20)),false)));return id;
    }
    UUID deadline(UUID project,LocalDate date,String status){UUID id=UUID.randomUUID();tx(()->projections.apply(envelope("MILESTONE",id,1,map("resourceId",id,"resourceType","MILESTONE","projectId",project,"projectTitle","Study","resourceName","Review","deadline",date,"status",status),false)));return id;}
    UUID report(UUID project){return UUID.fromString(reports.create(researcher,new CreateReport("PROJECT_PORTFOLIO_STATUS","CSV",Map.of("projectIds",List.of(project.toString()))),null).get("id").toString());}
    @Test void dashboardsScopeCountsAndCurrentDeadlines() throws Exception {
        UUID a=project("Visible"),b=project("Secret");visible.remove(b);deadline(a,LocalDate.now(ZoneOffset.UTC).minusDays(1),"PENDING");deadline(b,LocalDate.now(ZoneOffset.UTC),"PENDING");
        mvc.perform(get("/api/v1/dashboards/researcher").with(as(researcher))).andExpect(status().isOk()).andExpect(jsonPath("$.projects.length()").value(1)).andExpect(jsonPath("$.upcomingDeadlines[0].deadlineState").value("OVERDUE"));
        mvc.perform(get("/api/v1/dashboards/coordinator").with(as(researcher))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboards/coordinator").with(as(coordinator))).andExpect(status().isOk()).andExpect(jsonPath("$.counts.activeProjects").value(1));
        mvc.perform(get("/api/v1/dashboards/projects/"+a).with(as(outsider))).andExpect(status().isNotFound());
    }
    @Test void pagingDoesNotLeakUnauthorizedItemsAndBindsFilters() throws Exception {
        UUID a=project("Visible"),b=project("Hidden");visible.remove(b);deadline(a,LocalDate.now(ZoneOffset.UTC),"PENDING");deadline(a,LocalDate.now(ZoneOffset.UTC).plusDays(1),"PENDING");deadline(b,LocalDate.now(ZoneOffset.UTC),"PENDING");
        var first=queries.deadlines(researcher,null,Map.of("limit","1","state","UPCOMING"));assertThat((List<?>)first.get("items")).hasSize(1);
        var second=queries.deadlines(researcher,null,Map.of("limit","1","state","UPCOMING","cursor",first.get("nextCursor").toString()));assertThat((List<?>)second.get("items")).hasSize(1);assertThat(second.get("nextCursor")).isNull();
        assertThatThrownBy(()->queries.deadlines(researcher,null,Map.of("state","OVERDUE","cursor",first.get("nextCursor").toString()))).isInstanceOf(ApiException.class);
        mvc.perform(get("/api/v1/deadlines?type=UNKNOWN").with(as(researcher))).andExpect(status().isBadRequest());
    }
    @Test void versionsAndTombstonesPreventResurrection() {
        UUID id=project("Old");tx(()->projections.apply(envelope("PROJECT",id,3,Map.of("projectId",id),true)));
        tx(()->projections.apply(envelope("PROJECT",id,2,Map.of("projectId",id,"title","Stale"),false)));
        var state=store.one("select * from projection_state where aggregate_id=:id",map("id",id)).orElseThrow();assertThat(state.get("deleted")).isEqualTo(true);assertThat(((Number)state.get("sourceVersion")).longValue()).isEqualTo(3);
    }
    Map<String,Object> event(UUID project,UUID eventId) {return map("eventId",eventId,"eventType","ProjectChanged","eventVersion",1,"occurredAt",Instant.now(),"producer","project-execution-service","correlationId","test-event","actor",map("type","SERVICE","id","project-execution-service"),"aggregate",map("type","PROJECT","id",project,"version",2),"data",map("projectId",project));}
    @Test void inboxIsDurableIdempotentAndRejectsConflictingIds() {
        UUID id=project("Initial"),eventId=UUID.randomUUID();var e=event(id,eventId);byte[] bytes=json.writeValueAsBytes(e);
        projections.accept(bytes);projections.accept(bytes);assertThat(store.query("select id from incoming_event",Map.of())).hasSize(1);
        e.put("eventType","ProjectClosed");assertThatThrownBy(()->projections.accept(json.writeValueAsBytes(e))).isInstanceOf(ApiException.class);
        when(remote.state("project","PROJECT",id)).thenReturn(envelope("PROJECT",id,4,map("projectId",id,"title","Fresh"),false));
        projections.process(eventId);projections.process(eventId);assertThat(store.query("select * from processed_event",Map.of())).hasSize(1);
        assertThat(store.one("select source_version from projection_state where aggregate_id=:id",map("id",id)).orElseThrow().get("sourceVersion")).isEqualTo(4L);
    }
    @Test void malformedEventsAreRejectedBeforeInboxWrite() {
        UUID id=UUID.randomUUID();var e=event(id,UUID.randomUUID());e.put("producer","untrusted");assertThatThrownBy(()->projections.accept(json.writeValueAsBytes(e))).isInstanceOf(ApiException.class);assertThat(store.query("select * from incoming_event",Map.of())).isEmpty();
    }
    @Test void reportsAreDurablePrivateAndRevalidateRevocationAtDownload() {
        UUID project=project("=HYPERLINK(\"bad\")");UUID id=report(project);
        assertThat(reports.get(researcher,id).get("status")).isEqualTo("QUEUED");reports.generate(id);
        assertThat(reports.get(researcher,id)).doesNotContainKeys("artifact","resourceIds");
        assertThatThrownBy(()->reports.get(outsider,id)).isInstanceOf(ApiException.class);
        var grant=reports.authorize(researcher,id);String token=grant.get("url").toString().split("token=")[1];
        assertThat(new String(reports.download(researcher,id,token),java.nio.charset.StandardCharsets.UTF_8)).contains("'=HYPERLINK");
        visible.clear();assertThatThrownBy(()->reports.download(researcher,id,token)).isInstanceOf(ApiException.class).hasMessageContaining("access");
    }
    @Test void expiryAndCancellationEraseAccess() {
        UUID project=project("Study"),id=report(project);reports.generate(id);var grant=reports.authorize(researcher,id);String token=grant.get("url").toString().split("token=")[1];
        store.update("report_job",id,map("expiresAt",Instant.now().minusSeconds(1)));assertThatThrownBy(()->reports.download(researcher,id,token)).isInstanceOf(ApiException.class);
        reports.cancel(researcher,id);assertThat(store.row("report_job",id).get("artifact")).isNull();assertThat(store.query("select * from download_grant",Map.of())).isEmpty();
        UUID queued=report(project);reports.cancel(researcher,queued);reports.generate(queued);assertThat(reports.get(researcher,queued).get("status")).isEqualTo("CANCELLED");
    }
    @Test void exportRevalidatesBeforeGenerationAndDoesNotExpandScope() {
        UUID a=project("A"),id=report(a);project("B");reports.generate(id);
        assertThat(new String((byte[])store.row("report_job",id).get("artifact"),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\"B\"");
        UUID denied=report(a);visible.remove(a);assertThatThrownBy(()->reports.generate(denied)).isInstanceOf(ApiException.class);assertThat(store.row("report_job",denied).get("artifact")).isNull();
    }
    @Test void reportIdempotencyAndParameters() {
        UUID a=project("A");var input=new CreateReport("PROJECT_PORTFOLIO_STATUS","CSV",Map.of("projectIds",List.of(a.toString())));
        assertThat(reports.create(researcher,input,"same").get("id").toString()).isEqualTo(reports.create(researcher,input,"same").get("id").toString());
        assertThatThrownBy(()->reports.create(researcher,new CreateReport("PROJECT_PORTFOLIO_STATUS","CSV",Map.of()),"same")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.create(researcher,new CreateReport("PROJECT_PORTFOLIO_STATUS","PDF",Map.of()),null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.create(researcher,new CreateReport("PROJECT_PORTFOLIO_STATUS","CSV",Map.of("asOfDate","2020-01-01")),null)).isInstanceOf(ApiException.class);
    }
    @Test void rebuildAtomicallyRemovesOldRowsAndPreservesNewerConcurrentEvents() {
        UUID stale=project("Removed"),fresh=UUID.randomUUID(),concurrent=UUID.randomUUID();
        var job=rebuilds.create(new Rebuild("PROJECTS",Map.of(),"Reconcile"));UUID id=UUID.fromString(job.get("jobId").toString());
        when(remote.snapshot("project","PROJECTS",null)).thenAnswer(i->{
            tx(()->projections.apply(envelope("PROJECT",fresh,9,map("projectId",fresh,"title","Concurrent newest"),false)));
            tx(()->projections.apply(envelope("PROJECT",concurrent,1,map("projectId",concurrent),false)));
            return map("snapshotId",UUID.randomUUID(),"asOf",Instant.now(),"items",List.of(envelope("PROJECT",fresh,3,map("projectId",fresh,"title","Snapshot"),false)));});
        rebuilds.run(id);assertThat(rebuilds.get(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat(store.one("select * from projection_state where aggregate_id=:id",map("id",stale))).isEmpty();
        assertThat(store.one("select source_version from projection_state where aggregate_id=:id",map("id",fresh)).orElseThrow().get("sourceVersion")).isEqualTo(9L);
        assertThat(store.one("select * from projection_state where aggregate_id=:id",map("id",concurrent))).isPresent();
    }
    @Test void failedRebuildKeepsServingExistingProjection() {
        UUID a=project("Keep");var job=rebuilds.create(new Rebuild("PROJECTS",Map.of(),"Test failure"));UUID id=UUID.fromString(job.get("jobId").toString());
        when(remote.snapshot(anyString(),anyString(),any())).thenThrow(ApiException.unavailable());rebuilds.run(id);
        assertThat(rebuilds.get(id).get("status")).isEqualTo("FAILED");assertThat(store.one("select * from projection_state where aggregate_id=:id",map("id",a))).isPresent();
    }
    @Test void proposalEventsUpdateAssignmentsAndEvaluatorDashboard() throws Exception {
        Caller evaluator=new Caller(researcher.id(),Set.of("EVALUATOR"));UUID proposal=UUID.randomUUID(),assignment=UUID.randomUUID();visible.add(proposal);
        var event=event(proposal,UUID.randomUUID());event.put("producer","proposal-service");event.put("eventType","EvaluatorAssigned");event.put("aggregate",map("type","PROPOSAL","id",proposal,"version",2));event.put("data",map("proposalId",proposal));
        var state=map("proposalId",proposal,"proposalTitle","Study","proposalStatus","UNDER_REVIEW","researchAreaCode","ENGINEERING","updatedAt",Instant.now(),"assignments",List.of(map("assignmentId",assignment,"evaluatorUserId",researcher.id(),"assignmentStatus","ACCEPTED","dueDate",LocalDate.now(ZoneOffset.UTC))));
        when(remote.state("proposal","PROPOSAL",proposal)).thenReturn(envelope("PROPOSAL",proposal,2,state,false));
        UUID eventId=UUID.fromString(event.get("eventId").toString());projections.accept(json.writeValueAsBytes(event));projections.process(eventId);
        var dashboard=queries.dashboard(evaluator,"evaluator",null,Map.of());assertThat(((Map<?,?>)dashboard.get("counts")).get("pendingEvaluations")).isEqualTo(1L);
        var changed=event(proposal,UUID.randomUUID());changed.put("producer","proposal-service");changed.put("eventType","ProposalChanged");changed.put("aggregate",map("type","PROPOSAL","id",proposal,"version",3));changed.put("data",map("proposalId",proposal));state.put("assignments",List.of());
        when(remote.state("proposal","PROPOSAL",proposal)).thenReturn(envelope("PROPOSAL",proposal,3,state,false));UUID next=UUID.fromString(changed.get("eventId").toString());projections.accept(json.writeValueAsBytes(changed));projections.process(next);
        assertThat((List<?>)queries.dashboard(evaluator,"evaluator",null,Map.of()).get("assignments")).isEmpty();
    }
    @Test void scopedRebuildLeavesOtherProjectsUntouched() {
        UUID a=project("A"),b=project("B");var job=rebuilds.create(new Rebuild("PROJECTS",Map.of("projectIds",List.of(a.toString())),"Scoped"));
        when(remote.snapshot("project","PROJECTS",null)).thenReturn(map("snapshotId",UUID.randomUUID(),"asOf",Instant.now(),"items",List.of(envelope("PROJECT",a,2,map("projectId",a,"title","Updated"),false),envelope("PROJECT",b,2,map("projectId",b,"title","Do not replace"),false))));
        rebuilds.run(UUID.fromString(job.get("jobId").toString()));
        assertThat(store.one("select source_version from projection_state where aggregate_id=:id",map("id",a)).orElseThrow().get("sourceVersion")).isEqualTo(2L);
        assertThat(store.one("select source_version from projection_state where aggregate_id=:id",map("id",b)).orElseThrow().get("sourceVersion")).isEqualTo(1L);
    }
    @Test void evaluationRebuildEnrichesCurrentMetadataForResearchAreaFilters() {
        UUID proposal=UUID.randomUUID(),assignment=UUID.randomUUID();visible.add(proposal);
        var state=map("assignmentId",assignment,"proposalId",proposal,"evaluatorUserId",researcher.id(),"assignmentStatus","ASSIGNED");
        when(remote.snapshot("proposal","EVALUATIONS",null)).thenReturn(map("snapshotId",UUID.randomUUID(),"asOf",Instant.now(),"items",List.of(envelope("EVALUATOR_ASSIGNMENT",assignment,2,state,false))));
        when(remote.state("proposal","PROPOSAL",proposal)).thenReturn(envelope("PROPOSAL",proposal,3,map("proposalId",proposal,"proposalTitle","Study","proposalStatus","UNDER_REVIEW","researchAreaCode","ENGINEERING","updatedAt",Instant.now(),"assignments",List.of(state)),false));
        var job=rebuilds.create(new Rebuild("EVALUATIONS",Map.of(),"Bootstrap"));rebuilds.run(UUID.fromString(job.get("jobId").toString()));assertThat(rebuilds.get(UUID.fromString(job.get("jobId").toString())).get("status")).isEqualTo("SUCCEEDED");
        assertThat((List<?>)queries.dashboard(new Caller(researcher.id(),Set.of("EVALUATOR")),"evaluator",null,Map.of("researchArea","ENGINEERING")).get("assignments")).hasSize(1);
    }
    @Test void securitySeparatesPublicAndWorkloadTokens() throws Exception {
        mvc.perform(get("/api/v1/report-definitions")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/internal/v1/projections/rebuilds/"+UUID.randomUUID()).with(as(coordinator))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/report-definitions").with(workload("reporting-admin","report-service"))).andExpect(status().isForbidden());
        String body=json.writeValueAsString(new Rebuild("PROJECTS",Map.of(),"Bootstrap"));
        mvc.perform(post("/internal/v1/projections/rebuilds").with(workload("reporting-admin","report-service")).contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(header().exists("Location"));
        mvc.perform(post("/internal/v1/projections/rebuilds").with(workload("intruder","report-service")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
