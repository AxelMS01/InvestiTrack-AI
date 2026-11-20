package com.example.proposalservice;

import com.example.proposalservice.api.*;
import com.example.proposalservice.api.ApiModels.*;
import com.example.proposalservice.application.*;
import com.example.proposalservice.infrastructure.*;
import com.example.proposalservice.infraestructure.persistence.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.example.proposalservice.application.ProposalApplicationService.fields;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProposalIntegrationTests extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired IntegrationStore store;
    @Autowired ProposalApplicationService service;
    @Autowired ProposalInbox inbox;
    @Autowired ProposalProjections projections;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @MockitoBean IdentityClient identity;
    @MockitoBean JwtDecoder decoder;
    UUID owner,coordinator,evaluator,other;
    Map<UUID,Set<String>> profiles;
    TransactionTemplate tx;
    @BeforeEach void fixtures() {
        clean();owner=UUID.randomUUID();coordinator=UUID.randomUUID();evaluator=UUID.randomUUID();other=UUID.randomUUID();
        profiles=new ConcurrentHashMap<>();profiles.put(owner,Set.of("RESEARCHER"));profiles.put(coordinator,Set.of("COORDINATOR"));profiles.put(evaluator,Set.of("EVALUATOR"));profiles.put(other,Set.of("RESEARCHER"));
        when(identity.resolve(any())).thenAnswer(invocation->{Jwt token=invocation.getArgument(0);UUID id=UUID.fromString(token.getSubject().substring("institutional|".length()));var roles=profiles.getOrDefault(id,Set.of());return caller(id,roles);});
        tx=new TransactionTemplate(manager);tx.executeWithoutResult(status->{
            var area=new ResearchAreaEntity("ENGINEERING","Engineering","Research area",owner);area.setCode("ENGINEERING");em.persist(area);
            var criterion=new EvaluationCriterionEntity("Merit","Scientific merit",BigDecimal.ONE,new BigDecimal("5"),BigDecimal.ONE,LocalDate.now().minusDays(1),owner);criterion.setCode("MERIT");em.persist(criterion);
        });
    }
    void clean(){store.execute("drop trigger if exists fail_proposal_outbox on outbox_event",Map.of());store.execute("drop function if exists fail_proposal_outbox()",Map.of());store.execute("truncate proposal,research_area,evaluation_criterion,proposal_api_record,outbox_event,processed_event,proposal_incoming_event,proposal_projection_export,proposal_tombstone,proposal_document_revocation cascade",Map.of());}
    @AfterEach void cleanup(){clean();}
    Caller caller(UUID user,Set<String> roles){return new Caller(user,roles.contains("COORDINATOR"),roles.contains("ADMIN"),roles.contains("RESEARCHER"),roles.contains("AUDITOR"),roles.contains("EVALUATOR"));}
    RequestPostProcessor as(UUID user){return jwt().jwt(j->j.subject("institutional|"+user).audience(List.of("research-platform-api")).claim("roles",List.of("ADMIN","COORDINATOR","EVALUATOR")));}
    RequestPostProcessor workload(String client,String scope){return jwt().jwt(j->j.subject(client).audience(List.of("proposal-service")).claim("client_id",client)).authorities(new SimpleGrantedAuthority("SCOPE_"+scope));}
    String body(Object value){return json.writeValueAsString(value);}
    @SuppressWarnings("unchecked") Map<String,Object> parsed(MvcResult result){return json.readValue(result.getResponse().getContentAsByteArray(),Map.class);}
    String path(Map<String,Object> proposal){return "/api/v1/proposals/"+proposal.get("id");}
    String etag(String path,UUID user)throws Exception{return mvc.perform(get(path).with(as(user))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");}
    Map<String,Object> create()throws Exception {
        return parsed(mvc.perform(post("/api/v1/proposals").with(as(owner)).contentType("application/json").content(body(fields("title","Sensor study","abstract","SECRET-ABSTRACT","researchProblem","SECRET-PROBLEM","methodology","SECRET-METHOD","expectedResults","SECRET-RESULTS","researchAreaCode","ENGINEERING","estimatedDurationMonths",12,"objectives",List.of(fields("type","GENERAL","description","SECRET-OBJECTIVE")))))).andExpect(status().isCreated()).andReturn());
    }
    void transition(String path,String state,UUID actor,Object plan)throws Exception {
        var input=fields("targetStatus",state);if(plan!=null)input.put("creationPlan",plan);
        mvc.perform(post(path+"/transitions").with(as(actor)).header("If-Match",etag(path,actor)).contentType("application/json").content(body(input))).andExpect(status().isOk());
    }
    Map<String,Object> plan(){return fields("coordinatorId",coordinator,"startDate","2026-12-01","plannedEndDate","2027-12-01");}
    String readyToApprove()throws Exception {
        String path=path(create());transition(path,"SUBMITTED",owner,null);
        mvc.perform(post(path+"/evaluator-assignments").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content(body(fields("evaluatorId",evaluator,"dueDate","2026-12-01")))).andExpect(status().isCreated());
        transition(path,"UNDER_REVIEW",coordinator,null);
        var evaluation=parsed(mvc.perform(post(path+"/evaluations").with(as(evaluator)).contentType("application/json").content(body(fields("criteria",List.of(fields("criterionCode","MERIT","score",4,"comment","SECRET-REVIEW")),"observations","SECRET-OBSERVATION","decision","APPROVE")))).andExpect(status().isCreated()).andReturn());
        String evaluationPath=path+"/evaluations/"+evaluation.get("id");mvc.perform(post(evaluationPath+"/submission").with(as(evaluator)).header("If-Match",etag(evaluationPath,evaluator))).andExpect(status().isOk());return path;
    }
    Map<String,Object> approve(String path)throws Exception {transition(path,"APPROVED",coordinator,plan());UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));return service.approved(id,null);}
    long count(String table){return ((Number)store.query("select count(*) as count from "+table,Map.of()).getFirst().get("count")).longValue();}

    @Test void approvalRequiresExplicitPlanAndProducesProjectCompatibleSnapshot()throws Exception {
        String path=readyToApprove();
        mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content("{\"targetStatus\":\"APPROVED\"}")).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("CREATION_PLAN_REQUIRED"));
        assertThat(count("proposal_decision")).isZero();
        var invalid=fields("targetStatus","APPROVED","creationPlan",fields("coordinatorId",coordinator,"startDate","2028-01-01","plannedEndDate","2027-01-01"));
        mvc.perform(post(path+"/transitions").with(as(coordinator)).header("If-Match",etag(path,coordinator)).contentType("application/json").content(body(invalid))).andExpect(status().is(422));
        var snapshot=approve(path);assertThat(snapshot).containsKeys("proposalId","approvalId","approvedAt","proposalVersion","title","researchAreaCode","principalResearcherId","collaborators","creationPlan");
        assertThat(snapshot.get("principalResearcherId")).isEqualTo(owner.toString());assertThat(((Map<?,?>)snapshot.get("creationPlan")).get("coordinatorId")).isEqualTo(coordinator.toString());
        var current=parsed(mvc.perform(get(path).with(as(owner))).andReturn());assertThat(((Number)snapshot.get("proposalVersion")).longValue()).isEqualTo(((Number)current.get("version")).longValue());
        verify(identity).active(coordinator,"COORDINATOR");
    }
    @Test void canonicalEventsContainNoProposalOrEvaluationBodies()throws Exception {
        String path=readyToApprove();var snapshot=approve(path);
        var rows=store.query("select id,payload from outbox_event order by occurred_at",Map.of());assertThat(rows).isNotEmpty();
        for(var row:rows){var event=(Map<?,?>)row.get("payload");assertThat(event.get("eventId")).isEqualTo(row.get("id").toString());assertThat(((Number)((Map<?,?>)event.get("aggregate")).get("version")).longValue()).isPositive();assertThat(body(event)).doesNotContain("SECRET-");}
        var approval=rows.stream().map(row->(Map<?,?>)row.get("payload")).filter(e->e.get("eventType").equals("ProposalApproved")).findFirst().orElseThrow();
        var data=(Map<?,?>)approval.get("data");assertThat(data.get("approvalId")).isEqualTo(snapshot.get("approvalId"));assertThat(data.get("proposalVersion")).isEqualTo(snapshot.get("proposalVersion"));
        assertThat(((Map<?,?>)approval.get("aggregate")).get("version")).isEqualTo(snapshot.get("proposalVersion"));
    }
    @Test void identityRoleRevocationAndForgedClaimsCannotElevateAccess()throws Exception {
        mvc.perform(post("/api/v1/proposals").with(as(evaluator)).contentType("application/json").content("{\"title\":\"Forbidden\"}")).andExpect(status().isForbidden());
        String path=readyToApprove();profiles.put(evaluator,Set.of());
        mvc.perform(get(path).with(as(evaluator))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/evaluation-assignments").with(as(evaluator))).andExpect(status().isForbidden());
        doThrow(new ApiException(503,"DEPENDENCY_UNAVAILABLE","Identity unavailable")).when(identity).resolve(any());
        mvc.perform(get(path).with(as(owner))).andExpect(status().isServiceUnavailable());
    }
    @Test void accessContextsAreLocalAndRestrictedToCorrectWorkloads()throws Exception {
        String path=path(create());String internal=path.replace("/api/v1/","/internal/v1/")+"/access-context";clearInvocations(identity);
        mvc.perform(get(internal).param("subjectId",owner.toString()).with(workload("identity-service","proposal:context"))).andExpect(status().isOk()).andExpect(jsonPath("$.subject.isOwner").value(true));
        mvc.perform(get(internal).param("subjectId",owner.toString()).with(workload("project-execution-service","proposal:context"))).andExpect(status().isForbidden());
        mvc.perform(get(internal).param("subjectId",owner.toString()).with(as(owner))).andExpect(status().isForbidden());verifyNoInteractions(identity);
    }
    @Test void approvalPinningAndProjectReferencesAreImmutableAndIdempotent()throws Exception {
        String path=readyToApprove();var snapshot=approve(path);String internal=path.replace("/api/v1/","/internal/v1/");var auth=workload("project-execution-service","proposal:project-integration");
        mvc.perform(get(internal+"/project-creation-data").param("approvalId",UUID.randomUUID().toString()).with(auth)).andExpect(status().isConflict());
        mvc.perform(get(internal+"/project-creation-data").param("approvalId",snapshot.get("approvalId").toString()).with(auth)).andExpect(status().isOk());
        UUID project=UUID.randomUUID();String reference=body(fields("approvalId",snapshot.get("approvalId"),"projectId",project));long before=count("outbox_event");
        for(int i=0;i<2;i++)mvc.perform(put(internal+"/project-reference").with(auth).contentType("application/json").content(reference)).andExpect(status().isNoContent());
        assertThat(count("outbox_event")).isEqualTo(before+1);assertThat(service.approved(UUID.fromString(snapshot.get("proposalId").toString()),null)).isEqualTo(snapshot);
        mvc.perform(get(path).with(as(owner))).andExpect(jsonPath("$.projectId").value(project.toString()));
    }
    Map<String,Object> projectEvent(Map<String,Object> snapshot,UUID project){return fields("eventId",UUID.randomUUID(),"eventType","ProjectCreated","eventVersion",1,"occurredAt",Instant.now(),"producer","project-execution-service","correlationId","approval-to-project-flow","actor",fields("type","SERVICE","id","project-execution-service"),"aggregate",fields("type","PROJECT","id",project,"version",1),"data",fields("projectId",project,"proposalId",snapshot.get("proposalId"),"approvalId",snapshot.get("approvalId"),"status","APPROVED","principalResearcherId",owner,"coordinatorId",coordinator,"plannedEndDate","2027-12-01"));}
    @Test void projectCreatedConsumerDeduplicatesAndRecordsConflictsForReview()throws Exception {
        var snapshot=approve(readyToApprove());var event=projectEvent(snapshot,UUID.randomUUID());byte[] message=body(event).getBytes(StandardCharsets.UTF_8);
        inbox.accept(message);inbox.accept(message);inbox.process((UUID)event.get("eventId"));inbox.process((UUID)event.get("eventId"));assertThat(count("processed_event")).isEqualTo(1);
        UUID id=UUID.fromString(snapshot.get("proposalId").toString());var caller=caller(owner,profiles.get(owner));assertThat(service.get(id,caller).get("projectId")).isEqualTo(((Map<?,?>)event.get("data")).get("projectId").toString());
        var conflicting=projectEvent(snapshot,UUID.randomUUID());inbox.accept(body(conflicting).getBytes(StandardCharsets.UTF_8));new ProposalJobs(store,inbox,manager).incoming();
        assertThat(store.one("select status from proposal_incoming_event where id=:id",fields("id",conflicting.get("eventId"))).orElseThrow().get("status")).isEqualTo("FAILED");assertThat(count("processed_event")).isEqualTo(1);
        var link=store.query("select payload from outbox_event where event_type='ProposalChanged' order by occurred_at desc limit 1",Map.of()).getFirst();assertThat(body(link.get("payload"))).contains("approval-to-project-flow").contains(event.get("eventId").toString());
    }
    @Test void approvalAndReferenceWritesRollBackWhenOutboxInsertionFails()throws Exception {
        String path=readyToApprove();UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));
        store.execute("create function fail_proposal_outbox() returns trigger language plpgsql as $$ begin if NEW.event_type='ProposalApproved' then raise exception 'Simulated outbox failure'; end if; return NEW; end $$",Map.of());
        store.execute("create trigger fail_proposal_outbox before insert on outbox_event for each row execute function fail_proposal_outbox()",Map.of());
        long version=((Number)service.get(id,caller(coordinator,profiles.get(coordinator))).get("version")).longValue();
        assertThatThrownBy(()->service.transition(id,new Transition(ProposalStatus.APPROVED,null,new CreationPlan(coordinator,LocalDate.of(2026,12,1),LocalDate.of(2027,12,1))),caller(coordinator,profiles.get(coordinator)),"\""+version+"\"")).isInstanceOf(RuntimeException.class);
        assertThat(service.get(id,caller(owner,profiles.get(owner))).get("status")).isEqualTo(ProposalStatus.UNDER_REVIEW);assertThat(count("proposal_decision")).isZero();
        assertThat(store.query("select id from proposal_api_record where id like 'approval:%'",Map.of())).isEmpty();
    }
    @Test void concurrentTransitionsWithOneEtagHaveExactlyOneWinner()throws Exception {
        String path=path(create());UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));String etag=etag(path,owner);Caller caller=caller(owner,profiles.get(owner));
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{try{service.transition(id,new Transition(ProposalStatus.SUBMITTED,null),caller,etag);return 200;}catch(ApiException e){return e.status();}}));
            var statuses=new ArrayList<Integer>();for(var task:tasks)statuses.add(task.get(15,TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,412);
        }
    }
    @Test void queryBoundCursorsCannotCrossUsersFiltersOrResources()throws Exception {
        create();create();create();
        var first=parsed(mvc.perform(get("/api/v1/proposals").with(as(owner)).param("limit","1")).andExpect(status().isOk()).andReturn());String cursor=((Map<?,?>)first.get("page")).get("nextCursor").toString();
        mvc.perform(get("/api/v1/proposals").with(as(other)).param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/proposals").with(as(owner)).param("cursor",cursor).param("q","other")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/proposals").with(as(owner)).param("cursor",cursor).param("limit","1")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void expiredIdempotencyRecordsAreReplacedWithoutDeletingHistory()throws Exception {
        String input="{\"title\":\"First\"}";
        mvc.perform(post("/api/v1/proposals").with(as(owner)).header("Idempotency-Key","key-1").contentType("application/json").content(input)).andExpect(status().isCreated());
        store.execute("update proposal_api_record set expires_at=now()-interval '1 hour' where id like 'request:%'",Map.of());
        mvc.perform(post("/api/v1/proposals").with(as(owner)).header("Idempotency-Key","key-1").contentType("application/json").content("{\"title\":\"Second\"}")).andExpect(status().isCreated());
        assertThat(count("proposal")).isEqualTo(2);assertThat(store.query("select id from proposal_api_record where id like 'history:%'",Map.of())).hasSize(2);
    }
    @Test void snapshotExportsStayConsistentAndExcludeConfidentialAssessmentText()throws Exception {
        readyToApprove();readyToApprove();var export=projections.snapshot("EVALUATIONS",null,1);assertThat(export.get("nextCursor")).isNotNull();
        store.execute("update proposal set title='Changed after snapshot'",Map.of());var next=projections.snapshot("EVALUATIONS",export.get("nextCursor").toString(),1);
        assertThat(body(next)).contains("Sensor study").doesNotContain("Changed after snapshot","SECRET-");
        mvc.perform(get("/internal/v1/projection-snapshots").param("projection","EVALUATIONS").with(workload("reporting-service","proposal:projection"))).andExpect(status().isOk());
        mvc.perform(get("/internal/v1/projection-snapshots").param("projection","EVALUATIONS").with(workload("identity-service","proposal:projection"))).andExpect(status().isForbidden());
    }
    @Test void publisherRequiresConfirmationAndKeepsFailedDeliveriesPending()throws Exception {
        create();var rabbit=mock(RabbitTemplate.class);var publisher=new OutboxPublisher(store,rabbit);
        doAnswer(invocation->{CorrelationData confirmation=invocation.getArgument(3);confirmation.getFuture().complete(new CorrelationData.Confirm(false,"test nack"));return null;}).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(CorrelationData.class));
        tx.executeWithoutResult(status->publisher.publish());var pending=store.query("select * from outbox_event",Map.of()).getFirst();assertThat(pending.get("publishedAt")).isNull();assertThat(((Number)pending.get("attemptCount")).intValue()).isEqualTo(1);
        doAnswer(invocation->{org.springframework.amqp.core.Message message=invocation.getArgument(2);var payload=json.readTree(message.getBody());assertThat(message.getMessageProperties().getMessageId()).isEqualTo(payload.path("eventId").asString());CorrelationData confirmation=invocation.getArgument(3);confirmation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),eq("proposal.ProposalCreated.v1"),any(org.springframework.amqp.core.Message.class),any(CorrelationData.class));
        store.execute("update outbox_event set next_attempt_at=now()",Map.of());tx.executeWithoutResult(status->publisher.publish());assertThat(store.query("select published_at from outbox_event",Map.of()).getFirst().get("publishedAt")).isNotNull();
    }
}
