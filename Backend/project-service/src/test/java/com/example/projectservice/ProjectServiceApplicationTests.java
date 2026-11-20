package com.example.projectservice;

import com.example.projectservice.api.*;
import com.example.projectservice.api.ApiModels.*;
import com.example.projectservice.application.*;
import com.example.projectservice.infrastructure.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"project.messaging.enabled=false","project.jobs.enabled=false","spring.rabbitmq.dynamic=false"})
@AutoConfigureMockMvc
class ProjectServiceApplicationTests {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("PROJECT_TEST_DATABASE_URL");
        if(url==null){postgres=new PostgreSQLContainer("postgres:16-alpine");postgres.start();properties.add("spring.datasource.url",postgres::getJdbcUrl);properties.add("spring.datasource.username",postgres::getUsername);properties.add("spring.datasource.password",postgres::getPassword);}
        else {if(!url.matches(".*[/]([^/?]*test[^/?]*)(\\?.*)?$"))throw new IllegalArgumentException("Use a dedicated test database whose name contains 'test'.");properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->System.getenv().getOrDefault("PROJECT_TEST_DATABASE_USERNAME","project_test"));properties.add("spring.datasource.password",()->System.getenv().getOrDefault("PROJECT_TEST_DATABASE_PASSWORD","project_test"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectStore store;
    @Autowired ProjectService service;
    @Autowired ProjectionService projections;
    @Autowired ApprovalInbox inbox;
    @Autowired DocumentHolds holds;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @MockitoBean RemoteServices remote;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean DomainEvents events;
    UUID owner,coordinator,outsider,student;
    Map<UUID,Set<String>> profiles;
    Map<UUID,Map<String,Object>> snapshots;
    @BeforeEach void fixtures() {
        store.execute("truncate project,incoming_event,processed_event,idempotency_record,outbox_event,proposal_reference_job,document_hold_intent,aggregate_tombstone,deadline_notice,document_revocation,projection_export cascade",Map.of());
        owner=UUID.randomUUID();coordinator=UUID.randomUUID();outsider=UUID.randomUUID();student=UUID.randomUUID();profiles=new HashMap<>();snapshots=new HashMap<>();
        profiles.put(owner,Set.of("RESEARCHER"));profiles.put(coordinator,Set.of("COORDINATOR"));profiles.put(outsider,Set.of("RESEARCHER"));profiles.put(student,Set.of("STUDENT"));
        when(remote.caller(any())).thenAnswer(invocation->{Jwt jwt=invocation.getArgument(0);UUID id=UUID.fromString(jwt.getSubject().substring("institutional|".length()));return new Caller(id,profiles.getOrDefault(id,Set.of()),jwt.getTokenValue());});
        when(remote.approved(any(),any())).thenAnswer(invocation->{var snapshot=snapshots.get((UUID)invocation.getArgument(0));if(snapshot==null)throw ApiException.unavailable();return snapshot;});
        when(remote.validateDocument(any(),any(),anyInt(),anyString(),any())).thenAnswer(invocation->map("valid",true,"documentVersion",invocation.getArgument(2),"sha256","a".repeat(64)));
    }
    RequestPostProcessor as(UUID id){return jwt().jwt(j->j.subject("institutional|"+id).audience(List.of("research-platform-api")).claim("roles",List.of("ADMIN","COORDINATOR")));}
    RequestPostProcessor workload(String client,String scope){return jwt().jwt(j->j.subject(client).audience(List.of("project-service")).claim("client_id",client)).authorities(new SimpleGrantedAuthority("SCOPE_"+scope));}
    Map<String,Object> snapshot(UUID principal) {
        UUID id=UUID.randomUUID();var snapshot=map("proposalId",id.toString(),"approvalId",UUID.randomUUID().toString(),"approvedAt","2026-11-01T00:00:00Z","proposalVersion",1,"title","Sensor research","abstract","Approved summary","researchAreaCode","ENGINEERING","principalResearcherId",principal.toString(),"collaborators",List.of(),"creationPlan",map("coordinatorId",coordinator.toString(),"startDate","2026-11-01","plannedEndDate","2028-04-30"));snapshots.put(id,snapshot);return snapshot;
    }
    String body(Object value){return json.writeValueAsString(value);}
    @SuppressWarnings("unchecked") Map<String,Object> decoded(MvcResult response){return json.readValue(response.getResponse().getContentAsByteArray(),Map.class);}
    Map<String,Object> create() throws Exception{return create(owner);}
    Map<String,Object> create(UUID principal)throws Exception {
        var snapshot=snapshot(principal);return decoded(mvc.perform(post("/api/v1/projects").with(as(coordinator)).contentType("application/json").content(body(map("proposalId",snapshot.get("proposalId")))))
            .andExpect(status().isCreated()).andExpect(header().string("ETag","\"1\"")).andExpect(header().exists("Location")).andReturn());
    }
    String projectPath(Map<String,Object> project){return "/api/v1/projects/"+project.get("id");}
    String etag(String path,UUID caller)throws Exception{return mvc.perform(get(path).with(as(caller))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");}
    Map<String,Object> addActivity(String path,int weight)throws Exception {
        return decoded(mvc.perform(post(path+"/activities").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json")
            .content(body(map("name","Calibrate sensors","responsibleUserId",owner,"startDate","2026-11-01","deadline","2027-01-01","weight",weight))))
            .andExpect(status().isCreated()).andReturn());
    }
    void projectTransition(String path,String status)throws Exception {mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content(body(map("targetStatus",status)))).andExpect(status().isOk());}
    void activityTransition(String path,String status)throws Exception {mvc.perform(post(path+"/transitions").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(map("targetStatus",status)))).andExpect(status().isOk());}
    long count(String table){return ((Number)store.query("select count(*) as count from "+table,Map.of()).getFirst().get("count")).longValue();}

    @Test void publicAuthenticationAndLiveRolesAreEnforced()throws Exception {
        mvc.perform(get("/api/v1/projects").header("X-Correlation-ID","test-401")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.correlationId").value("test-401"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        var snapshot=snapshot(owner);
        mvc.perform(post("/api/v1/projects").with(as(owner)).contentType("application/json").content(body(map("proposalId",snapshot.get("proposalId"))))).andExpect(status().isForbidden());
        assertThat(count("project")).isZero();
    }
    @Test void creationRequiresApprovedPlanAndIsDurablyIdempotent()throws Exception {
        var snapshot=snapshot(owner);var input=map("proposalId",snapshot.get("proposalId"));
        var request=post("/api/v1/projects").with(as(coordinator)).header("Idempotency-Key","create-1").contentType("application/json").content(body(input));
        String first=mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String retry=mvc.perform(post("/api/v1/projects").with(as(coordinator)).header("Idempotency-Key","create-1").contentType("application/json").content(body(input))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(retry)).isEqualTo(json.readTree(first));assertThat(count("project")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);assertThat(count("proposal_reference_job")).isEqualTo(1);
        snapshot.put("newOptionalProviderMetadata",Map.of("extra",true));
        mvc.perform(post("/api/v1/projects").with(as(coordinator)).contentType("application/json").content(body(input))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects").with(as(coordinator)).header("Idempotency-Key","create-1").contentType("application/json").content(body(map("proposalId",UUID.randomUUID())))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/projects").with(as(coordinator)).contentType("application/json").content(body(map("proposalId",snapshot.get("proposalId"),"plannedEndDate","2030-01-01")))).andExpect(status().isConflict());
    }
    @Test void missingApprovalDataAndUnavailableOwnersNeverCreateProjects()throws Exception {
        var snapshot=snapshot(owner);snapshot.remove("creationPlan");
        mvc.perform(post("/api/v1/projects").with(as(coordinator)).contentType("application/json").content(body(map("proposalId",snapshot.get("proposalId"))))).andExpect(status().isServiceUnavailable());
        assertThat(count("project")).isZero();assertThat(count("outbox_event")).isZero();
        var valid=snapshot(owner);doThrow(ApiException.unavailable()).when(remote).activeUser(any());
        mvc.perform(post("/api/v1/projects").with(as(coordinator)).contentType("application/json").content(body(map("proposalId",valid.get("proposalId"))))).andExpect(status().isServiceUnavailable());
        assertThat(count("project")).isZero();
    }
    @Test void visibilityIsAppliedBeforePaginationAndFilters()throws Exception {
        create();create();create(outsider);
        var page=decoded(mvc.perform(get("/api/v1/projects").with(as(owner)).param("limit","1")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.page.hasMore").value(true)).andReturn());
        var paging=(Map<?,?>)page.get("page");String cursor=paging.get("nextCursor").toString();
        mvc.perform(get("/api/v1/projects").with(as(owner)).param("limit","1").param("cursor",cursor)).andExpect(status().isOk()).andExpect(jsonPath("$.page.hasMore").value(false));
        mvc.perform(get("/api/v1/projects").with(as(outsider)).param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects").with(as(student))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/projects").with(as(owner)).param("researchArea","OTHER")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/projects").with(as(owner)).param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects").with(as(owner)).param("status","UNKNOWN")).andExpect(status().isBadRequest());
    }
    @Test void updatesEnforceVersionsAndRejectUnknownFields()throws Exception {
        String path=projectPath(create());
        mvc.perform(patch(path).with(as(owner)).contentType("application/json").content("{\"title\":\"Changed\"}")).andExpect(status().is(428));
        mvc.perform(patch(path).with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content("{\"title\":\"Changed\"}")).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        mvc.perform(patch(path).with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content("{\"title\":\"Stale\"}")).andExpect(status().isPreconditionFailed());
        mvc.perform(patch(path).with(as(owner)).header("If-Match","\"2\"").contentType("application/json").content("{\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch(path).with(as(owner)).header("If-Match","*").contentType("application/json").content("{\"title\":\"Bad\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(path).with(as(outsider))).andExpect(status().isNotFound());
    }
    @Test void weightedProgressExcludesCancelledWorkAndUsesActualCompletions()throws Exception {
        String path=projectPath(create());var first=addActivity(path,1);var second=addActivity(path,3);var cancelled=addActivity(path,9);
        activityTransition(path+"/activities/"+cancelled.get("id"),"CANCELLED");activityTransition(path+"/activities/"+first.get("id"),"IN_PROGRESS");activityTransition(path+"/activities/"+first.get("id"),"COMPLETED");
        mvc.perform(get(path+"/progress").with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.progressPercent").value(25.0)).andExpect(jsonPath("$.totalWeight").value(4));
        mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content("{\"targetStatus\":\"COMPLETED\"}")).andExpect(status().isConflict());
        assertThat(count("project_progress_snapshot")).isGreaterThan(0);
    }
    @Test void activityAndMilestoneRelationsCannotCrossProjects()throws Exception {
        String first=projectPath(create()),second=projectPath(create());
        var milestone=decoded(mvc.perform(post(first+"/milestones").with(as(owner)).header("If-Match",etag(first,owner)).contentType("application/json").content("{\"name\":\"Delivery\",\"deadline\":\"2027-01-01\"}")).andExpect(status().isCreated()).andReturn());
        mvc.perform(post(second+"/activities").with(as(owner)).header("If-Match",etag(second,owner)).contentType("application/json").content(body(map("name","Foreign milestone","responsibleUserId",owner,"startDate","2026-11-01","deadline","2027-01-01","milestoneId",milestone.get("id"))))).andExpect(status().isNotFound());
        mvc.perform(get(second+"/milestones/"+milestone.get("id")).with(as(owner))).andExpect(status().isNotFound());
        var activity=addActivity(first,1);
        mvc.perform(patch(first+"/activities/"+activity.get("id")).with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content(body(map("milestoneId",milestone.get("id"))))).andExpect(status().isOk());
        mvc.perform(delete(first+"/milestones/"+milestone.get("id")).with(as(owner)).header("If-Match","\"1\"")).andExpect(status().isConflict());
        mvc.perform(get(first+"/milestones/"+milestone.get("id")+"/activities").with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void activeMembershipAndPermissionsAreRevokedImmediately()throws Exception {
        var p=create();String path=projectPath(p);UUID id=UUID.fromString(p.get("id").toString());
        mvc.perform(post(path+"/members").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(map("userId",student,"projectRole","STUDENT","permissions",List.of("ACTIVITY_WRITE"))))).andExpect(status().isCreated());
        mvc.perform(get(path).with(as(student))).andExpect(status().isOk());
        mvc.perform(delete(path+"/members/"+owner).with(as(coordinator)).header("If-Match","\"1\"")).andExpect(status().isConflict());
        mvc.perform(delete(path+"/members/"+student).with(as(owner)).header("If-Match","\"1\"")).andExpect(status().isNoContent());
        mvc.perform(get(path).with(as(student))).andExpect(status().isNotFound());
        var context=service.accessContext(id,student);assertThat(((Map<?,?>)context.get("subject")).get("membershipStatus")).isEqualTo("REMOVED");assertThat(((Number)context.get("accessVersion")).longValue()).isEqualTo(3);
    }
    @Test void internalContextRequiresWorkloadScopeAndDoesNotCallIdentity()throws Exception {
        var p=create();String path="/internal/v1/projects/"+p.get("id")+"/access-context";clearInvocations(remote);
        mvc.perform(get(path).param("subjectId",owner.toString()).with(as(owner))).andExpect(status().isForbidden());
        mvc.perform(get(path).param("subjectId",owner.toString()).with(workload("untrusted","project:context"))).andExpect(status().isForbidden());
        mvc.perform(get(path).param("subjectId",owner.toString()).with(workload("identity-service","project:context"))).andExpect(status().isOk()).andExpect(jsonPath("$.subject.userId").value(owner.toString())).andExpect(jsonPath("$.subject.membershipStatus").value("ACTIVE"));
        verifyNoInteractions(remote);
    }
    @Test void documentValidationFailureCannotStoreEvidence()throws Exception {
        String path=projectPath(create());var item=addActivity(path,1);String itemPath=path+"/activities/"+item.get("id");
        when(remote.validateDocument(any(),any(),anyInt(),anyString(),any())).thenThrow(new ApiException(422,"DOCUMENT_REFERENCE_INVALID","Document unavailable"));
        mvc.perform(post(itemPath+"/evidence").with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content(body(map("documentId",UUID.randomUUID(),"documentVersion",1)))).andExpect(status().is(422));
        assertThat(count("evidence_link")).isZero();mvc.perform(get(itemPath).with(as(owner))).andExpect(header().string("ETag","\"1\""));
    }
    @Test void evidenceLinksAreVersionPinnedAndProtectedFromDeletion()throws Exception {
        String path=projectPath(create());var item=addActivity(path,1);String itemPath=path+"/activities/"+item.get("id");UUID document=UUID.randomUUID();
        mvc.perform(post(itemPath+"/evidence").with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content(body(map("documentId",document,"documentVersion",2,"description","Measurements")))).andExpect(status().isCreated()).andExpect(jsonPath("$.documentVersion").value(2));
        mvc.perform(delete(itemPath).with(as(owner)).header("If-Match",etag(itemPath,owner))).andExpect(status().isConflict());
        mvc.perform(delete(itemPath+"/evidence/"+document).with(as(owner)).header("If-Match",etag(itemPath,owner))).andExpect(status().isNoContent());
        mvc.perform(delete(itemPath).with(as(owner)).header("If-Match",etag(itemPath,owner))).andExpect(status().isNoContent());
        assertThat(count("aggregate_tombstone")).isEqualTo(1);
    }
    String readyForClosure()throws Exception {
        String path=projectPath(create());var activity=addActivity(path,1);projectTransition(path,"ACTIVE");activityTransition(path+"/activities/"+activity.get("id"),"IN_PROGRESS");activityTransition(path+"/activities/"+activity.get("id"),"COMPLETED");projectTransition(path,"COMPLETED");
        mvc.perform(put(path+"/final-report").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(map("documentId",UUID.randomUUID(),"documentVersion",1)))).andExpect(status().isOk());
        mvc.perform(post(path+"/final-report/submission").with(as(owner)).header("If-Match",etag(path+"/final-report",owner))).andExpect(status().isOk());return path;
    }
    @Test void finalReportsAndClosureReserveTheValidatedDocumentVersion()throws Exception {
        String path=readyForClosure();projectTransition(path,"CLOSED");
        mvc.perform(get(path).with(as(owner))).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(patch(path).with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content("{\"title\":\"Late edit\"}")).andExpect(status().isConflict());
        mvc.perform(post(path+"/final-report/reopening").with(as(coordinator)).header("If-Match",etag(path+"/final-report",owner)).contentType("application/json").content("{\"reason\":\"Late change\"}")).andExpect(status().isConflict());
        assertThat(count("document_hold_intent")).isEqualTo(1);store.execute("update document_hold_intent set updated_at=now()-interval '10 minutes'",Map.of());holds.reconcile();
        assertThat(store.query("select status from document_hold_intent",Map.of()).getFirst().get("status")).isEqualTo("CONFIRMED");verify(remote,never()).release(any(),any());
    }
    @Test void failedClosureRollsBackAndDurablySchedulesHoldRelease()throws Exception {
        String path=readyForClosure();doThrow(new IllegalStateException("Simulated outbox failure")).when(events).write(eq("ProjectClosed"),anyMap(),any(Caller.class),anyMap());
        assertThatThrownBy(()->mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content("{\"targetStatus\":\"CLOSED\"}"))).hasRootCauseInstanceOf(IllegalStateException.class);
        mvc.perform(get(path).with(as(owner))).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(store.query("select status from document_hold_intent",Map.of()).getFirst().get("status")).isEqualTo("RELEASE_PENDING");
        store.execute("update document_hold_intent set updated_at=now()-interval '10 minutes'",Map.of());holds.reconcile();verify(remote).release(any(),any());
        assertThat(store.query("select status from document_hold_intent",Map.of()).getFirst().get("status")).isEqualTo("RELEASED");
    }
    @Test void publicationsPersistAuthorsAndRejectDuplicateDois()throws Exception {
        String path=projectPath(create());var input=map("title","Sensors paper","type","JOURNAL_ARTICLE","authors",List.of("Ana Perez","Luis Gomez"),"doi","10.1000/sensors");
        var publication=decoded(mvc.perform(post(path+"/publications").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(input))).andExpect(status().isCreated()).andReturn());
        mvc.perform(get(path+"/publications/"+publication.get("id")).with(as(owner))).andExpect(jsonPath("$.authors[0]").value("Ana Perez"));assertThat(count("publication_author")).isEqualTo(2);
        mvc.perform(post(path+"/publications").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(input))).andExpect(status().isConflict());
        mvc.perform(delete(path+"/publications/"+publication.get("id")).with(as(owner)).header("If-Match","\"1\"")).andExpect(status().isNoContent());assertThat(count("publication_author")).isZero();
    }
    Map<String,Object> approvalEvent(Map<String,Object> snapshot) {return map("eventId",UUID.randomUUID(),"eventType","ProposalApproved","eventVersion",1,"occurredAt","2026-11-01T00:00:00Z","producer","proposal-service","correlationId","approval-flow","actor",map("type","USER","id",coordinator),"aggregate",map("type","PROPOSAL","id",snapshot.get("proposalId"),"version",1),"data",map("proposalId",snapshot.get("proposalId"),"approvalId",snapshot.get("approvalId"),"proposalVersion",1,"approvedAt",snapshot.get("approvedAt")));}
    @Test void duplicateApprovalAndManualCreationProduceOneProject()throws Exception {
        var snapshot=snapshot(owner);var event=approvalEvent(snapshot);byte[] bytes=body(event).getBytes(java.nio.charset.StandardCharsets.UTF_8);inbox.accept(bytes);inbox.accept(bytes);UUID eventId=(UUID)event.get("eventId");
        var command=new CreateProject(UUID.fromString(snapshot.get("proposalId").toString()),null,null,null,null);var caller=new Caller(coordinator,Set.of("COORDINATOR"),"token");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var manual=executor.submit(()->service.create(caller,command));var delivery=executor.submit(()->{inbox.process(eventId);return true;});manual.get(15,TimeUnit.SECONDS);delivery.get(15,TimeUnit.SECONDS);
        }
        inbox.process(eventId);assertThat(count("project")).isEqualTo(1);assertThat(count("processed_event")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void eventFailuresLeaveNoPartialDomainDataAndRetryDurably() {
        var snapshot=snapshot(owner);var event=approvalEvent(snapshot);inbox.accept(body(event).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doThrow(ApiException.unavailable()).when(remote).approved(any(),any());
        var jobs=new ProjectJobs(store,inbox,remote,events,holds,manager);jobs.incoming();
        assertThat(count("project")).isZero();assertThat(count("processed_event")).isZero();assertThat(count("outbox_event")).isZero();
        var pending=store.query("select status,attempt_count from incoming_event",Map.of()).getFirst();assertThat(pending.get("status")).isEqualTo("PENDING");assertThat(((Number)pending.get("attemptCount")).intValue()).isEqualTo(1);
    }
    @Test void conflictingOrUnsupportedEventsAreRejected() {
        var event=approvalEvent(snapshot(owner));inbox.accept(body(event).getBytes(java.nio.charset.StandardCharsets.UTF_8));event.put("correlationId","changed");
        assertThatThrownBy(()->inbox.accept(body(event).getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("EVENT_CONFLICT");
        event.put("eventId",UUID.randomUUID());event.put("eventVersion",2);assertThatThrownBy(()->inbox.accept(body(event).getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);assertThat(count("incoming_event")).isEqualTo(1);
    }
    @Test void outboxFailureRollsBackProjectMembersAndHistory() {
        var snapshot=snapshot(owner);doThrow(new IllegalStateException("No outbox write")).when(events).write(eq("ProjectCreated"),anyMap(),any(Caller.class),anyMap());
        assertThatThrownBy(()->service.create(new Caller(coordinator,Set.of("COORDINATOR"),"token"),new CreateProject(UUID.fromString(snapshot.get("proposalId").toString()),null,null,null,null))).isInstanceOf(IllegalStateException.class);
        assertThat(count("project")).isZero();assertThat(count("project_member")).isZero();assertThat(count("project_status_history")).isZero();assertThat(count("proposal_reference_job")).isZero();
    }
    @Test void projectionSnapshotsStayStableAcrossConcurrentUpdates()throws Exception {
        String first=projectPath(create()),second=projectPath(create());var export=projections.snapshot("PROJECTS",null,1);assertThat(export.get("nextCursor")).isNotNull();
        mvc.perform(patch(first).with(as(owner)).header("If-Match",etag(first,owner)).contentType("application/json").content("{\"title\":\"Changed after snapshot\"}")).andExpect(status().isOk());
        mvc.perform(patch(second).with(as(owner)).header("If-Match",etag(second,owner)).contentType("application/json").content("{\"title\":\"Changed after snapshot\"}")).andExpect(status().isOk());
        var next=projections.snapshot("PROJECTS",export.get("nextCursor").toString(),1);var row=(Map<?,?>)((List<?>)next.get("items")).getFirst();assertThat(((Map<?,?>)row.get("state")).get("title")).isEqualTo("Sensor research");
        mvc.perform(get("/internal/v1/projection-snapshots").param("projection","PROJECTS").with(workload("reporting-service","project:projection"))).andExpect(status().isOk());
    }
    @Test void idempotentRepliesDoNotBypassCurrentMembershipPermissions()throws Exception {
        String path=projectPath(create());
        mvc.perform(post(path+"/members").with(as(owner)).header("If-Match",etag(path,owner)).contentType("application/json").content(body(map("userId",outsider,"projectRole","RESEARCHER","permissions",List.of())))).andExpect(status().isCreated());
        var input=map("name","Cached work","responsibleUserId",owner,"startDate","2026-11-01","deadline","2027-01-01");String match=etag(path,outsider);
        mvc.perform(post(path+"/activities").with(as(outsider)).header("Idempotency-Key","cached-work").header("If-Match",match).contentType("application/json").content(body(input))).andExpect(status().isCreated());
        mvc.perform(patch(path+"/members/"+outsider).with(as(owner)).header("If-Match","\"1\"").contentType("application/json").content(body(map("projectRole","STUDENT","permissions",List.of())))).andExpect(status().isOk());
        mvc.perform(post(path+"/activities").with(as(outsider)).header("Idempotency-Key","cached-work").header("If-Match",match).contentType("application/json").content(body(input))).andExpect(status().isForbidden());
        assertThat(count("activity")).isEqualTo(1);
    }
    @Test void concurrentUpdatesWithTheSameVersionHaveOneWinner()throws Exception {
        var project=create();UUID id=UUID.fromString(project.get("id").toString());var caller=new Caller(owner,Set.of("RESEARCHER"),"token");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<Integer>>();
            for(String title:List.of("First edit","Second edit"))tasks.add(executor.submit(()->{try{service.patch(caller,id,new PatchProject(title,null,null,null,null,null,null),"\"1\"");return 200;}catch(ApiException e){return e.status;}}));
            var statuses=new ArrayList<Integer>();for(var task:tasks)statuses.add(task.get(15,TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,412);
        }
    }
    @Test void permanentProposalReferenceConflictsStopAutomaticRetries()throws Exception {
        create();doThrow(new ApiException(409,"PROJECT_REFERENCE_CONFLICT","Existing project differs")).when(remote).recordProject(any(),any(),any());
        var jobs=new ProjectJobs(store,inbox,remote,events,holds,manager);jobs.references();jobs.references();
        assertThat(store.query("select status from proposal_reference_job",Map.of()).getFirst().get("status")).isEqualTo("CONFLICT");verify(remote,times(1)).recordProject(any(),any(),any());
    }
    @Test void deniedDocumentHoldPreventsClosureAndLeavesRecoverableIntent()throws Exception {
        String path=readyForClosure();doThrow(new ApiException(409,"DOCUMENT_CHANGED","Document changed concurrently")).when(remote).hold(any(),any(),anyInt(),anyString(),any());
        mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content("{\"targetStatus\":\"CLOSED\"}")).andExpect(status().isConflict());
        mvc.perform(get(path).with(as(owner))).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(store.query("select status from document_hold_intent",Map.of()).getFirst().get("status")).isEqualTo("RELEASE_PENDING");
    }
    @Test void overdueEventsAreDeduplicated()throws Exception {
        String path=projectPath(create());addActivity(path,1);store.execute("update activity set start_date=:start,deadline=:end",map("start",LocalDate.now(ZoneOffset.UTC).minusDays(10),"end",LocalDate.now(ZoneOffset.UTC).minusDays(1)));
        var jobs=new ProjectJobs(store,inbox,remote,events,holds,manager);jobs.deadlines();jobs.deadlines();assertThat(count("deadline_notice")).isEqualTo(1);
        assertThat(store.query("select id from outbox_event where event_type='DeadlineOverdue'",Map.of())).hasSize(1);
    }
}
