package com.example.identityservice;

import com.example.identityservice.api.ApiModels.*;
import com.example.identityservice.domain.User;
import com.example.identityservice.domain.User.Status;
import com.example.identityservice.infrastructure.*;
import com.example.identityservice.api.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"identity.outbox.enabled=false","spring.rabbitmq.dynamic=false"})
@AutoConfigureMockMvc
class IdentityServiceApplicationTests {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        String url=System.getenv("IDENTITY_TEST_DATABASE_URL");
        if(url==null) {
            postgres=new PostgreSQLContainer("postgres:16-alpine"); postgres.start();
            props.add("spring.datasource.url",postgres::getJdbcUrl);
            props.add("spring.datasource.username",postgres::getUsername);
            props.add("spring.datasource.password",postgres::getPassword);
        } else {
            props.add("spring.datasource.url",()->url);
            props.add("spring.datasource.username",()->System.getenv().getOrDefault("IDENTITY_TEST_DATABASE_USERNAME","identity"));
            props.add("spring.datasource.password",()->System.getenv().getOrDefault("IDENTITY_TEST_DATABASE_PASSWORD",""));
        }
    }
    @Autowired MockMvc mvc;
    @Autowired IdentityRepository repo;
    @Autowired ObjectMapper json;
    @Autowired DomainEvents events;
    @Autowired com.example.identityservice.application.IdentityService identity;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OwnerClient owners;
    UUID admin,user,coordinator;
    @BeforeEach void setup() {
        repo.db().sql("truncate app_user,user_role,user_status_history,outbox_event,idempotency_record").update();
        admin=fixture("institutional|admin",Status.ACTIVE,"ADMIN");
        user=fixture("institutional|researcher",Status.ACTIVE,"RESEARCHER");
        coordinator=fixture("institutional|coordinator",Status.ACTIVE,"COORDINATOR");
    }
    UUID fixture(String subject,Status status,String... roles) {
        UUID id=UUID.randomUUID(); var now=Instant.now();
        repo.insert(new User(id,subject,null,subject.replace('|','.')+"@university.test","Name "+subject,null,null,status,"en",null,now,id,now,id,1));
        repo.assign(id,List.of(roles),id,"Test fixture"); return id;
    }
    RequestPostProcessor as(String subject) { return jwt().jwt(j->j.subject(subject).audience(List.of("research-platform-api"))); }
    RequestPostProcessor workload(String scopes) {
        return jwt().jwt(j->j.subject("proposal-service").audience(List.of("identity-service")).claim("client_id","proposal-service").claim("scope",scopes))
            .authorities(Arrays.stream(scopes.split(" ")).map(s->new SimpleGrantedAuthority("SCOPE_"+s)).toArray(SimpleGrantedAuthority[]::new));
    }
    String createBody(String subject,String email) { return json.writeValueAsString(Map.of("identitySubject",subject,"email",email,"displayName","New User","roles",List.of("STUDENT"))); }
    @Test void auditRoleCanBeAssignedWithoutGrantingOtherDomainCapabilities() throws Exception {
        String body=json.writeValueAsString(Map.of("identitySubject","institutional|auditor","email","auditor@university.test","displayName","Institutional auditor","roles",List.of("AUDITOR")));
        mvc.perform(post("/api/v1/users").with(as("institutional|admin")).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.roles[0]").value("AUDITOR")).andExpect(jsonPath("$.capabilities").isEmpty());
        UUID auditor=repo.subject("institutional|auditor").orElseThrow().id();
        var auditWorkload=jwt().jwt(j->j.subject("audit-service").audience(List.of("identity-service")).claim("client_id","audit-service")).authorities(new SimpleGrantedAuthority("SCOPE_identity:status"));
        mvc.perform(get("/internal/v1/users/"+auditor+"/status").with(auditWorkload)).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("AUDITOR"));
    }
    @Test void anonymousRequestsAreRejectedWithProblemDetailsAndCorrelation() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Correlation-ID","test-correlation"))
            .andExpect(status().isUnauthorized()).andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.correlationId").value("test-correlation"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
    @Test void subjectMappingUsesInstitutionalSubjectAndCurrentDatabaseRoles() throws Exception {
        mvc.perform(get("/api/v1/me").with(as("institutional|researcher")))
            .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""))
            .andExpect(jsonPath("$.id").value(user.toString())).andExpect(jsonPath("$.roles[0]").value("RESEARCHER"));
        mvc.perform(get("/api/v1/users").with(jwt().jwt(j->j.subject("institutional|researcher").audience(List.of("research-platform-api")).claim("roles",List.of("ADMIN")))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").with(as("not-provisioned"))).andExpect(status().isForbidden());
    }
    @Test void provisioningIsIdempotentAndRejectsConflictingRequestsAndDuplicateEmails() throws Exception {
        String body=createBody("external-subject","new@university.test");
        var first=mvc.perform(post("/api/v1/users").with(as("institutional|admin")).header("Idempotency-Key","create-1").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn();
        var second=mvc.perform(post("/api/v1/users").with(as("institutional|admin")).header("Idempotency-Key","create-1").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(repo.db().sql("select count(*) from outbox_event").query(Long.class).single()).isEqualTo(2);
        mvc.perform(post("/api/v1/users").with(as("institutional|admin")).header("Idempotency-Key","create-1").contentType("application/json").content(createBody("other","other@university.test")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v1/users").with(as("institutional|admin")).contentType("application/json").content(createBody("other","NEW@university.test")))
            .andExpect(status().isConflict());
    }
    @Test void profileUpdatesEnforceOptimisticConcurrencyAndPersonalFields() throws Exception {
        mvc.perform(patch("/api/v1/me").with(as("institutional|researcher")).contentType("application/json").content("{\"displayName\":\"Updated\"}"))
            .andExpect(status().is(428));
        mvc.perform(patch("/api/v1/me").with(as("institutional|researcher")).header("If-Match","\"1\"").contentType("application/json").content("{\"displayName\":\"Updated\"}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        mvc.perform(patch("/api/v1/me").with(as("institutional|researcher")).header("If-Match","\"1\"").contentType("application/json").content("{\"displayName\":\"Stale\"}"))
            .andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/api/v1/me").with(as("institutional|researcher")).header("If-Match","\"2\"").contentType("application/json").content("{\"email\":\"evil@university.test\"}"))
            .andExpect(status().is(422));
        mvc.perform(patch("/api/v1/me").with(as("institutional|researcher")).header("If-Match","\"2\"").contentType("application/json").content("{\"roles\":[\"ADMIN\"]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void lastAdministratorCannotBeDeactivatedOrLoseAdmin() throws Exception {
        mvc.perform(post("/api/v1/users/"+admin+"/deactivation").with(as("institutional|admin")).header("If-Match","\"1\"").contentType("application/json").content("{\"reason\":\"Leaving\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"));
        mvc.perform(put("/api/v1/users/"+admin+"/roles").with(as("institutional|admin")).header("If-Match","\"1\"").contentType("application/json").content("{\"roles\":[],\"reason\":\"Change\"}"))
            .andExpect(status().isConflict());
        assertThat(repo.roles(admin)).containsExactly("ADMIN");
    }
    @Test void deactivateReactivateAndRolesProduceAtomicVersionedEventsAndHistory() throws Exception {
        mvc.perform(post("/api/v1/users/"+user+"/deactivation").with(as("institutional|admin")).header("If-Match","\"1\"").contentType("application/json").content("{\"reason\":\"Suspended\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DEACTIVATED"));
        mvc.perform(get("/api/v1/me").with(as("institutional|researcher"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/"+user+"/deactivation").with(as("institutional|admin")).header("If-Match","\"2\""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        mvc.perform(post("/api/v1/users/"+user+"/roles/EVALUATOR").with(as("institutional|admin")).header("If-Match","\"3\"").contentType("application/json").content("{\"reason\":\"Committee\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        mvc.perform(post("/api/v1/users/"+user+"/roles/EVALUATOR").with(as("institutional|admin")).header("If-Match","\"4\"").contentType("application/json").content("{\"reason\":\"Retry\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        assertThat(repo.db().sql("select count(*) from user_status_history").query(Long.class).single()).isEqualTo(2);
        assertThat(repo.db().sql("select count(*) from outbox_event").query(Long.class).single()).isEqualTo(3);
        var payload=repo.db().sql("select cast(payload as text) from outbox_event where event_type='UserRolesChanged'").query(String.class).single();
        var event=json.readTree(payload);
        assertThat(event.path("aggregate").path("version").asLong()).isEqualTo(4);
        assertThat(event.path("data").path("identityVersion").asLong()).isEqualTo(4);
        assertThat(event.path("actor").path("id").asString()).isEqualTo(admin.toString());
    }
    @Test void coordinatorLookupOmitsSensitiveProfileAndSupportsBoundCursorPages() throws Exception {
        mvc.perform(get("/api/v1/users/"+user).with(as("institutional|coordinator")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.roles").doesNotExist());
        var result=mvc.perform(get("/api/v1/users?limit=1").with(as("institutional|coordinator"))).andExpect(status().isOk()).andExpect(jsonPath("$.page.hasMore").value(true)).andReturn();
        String cursor=json.readTree(result.getResponse().getContentAsString()).path("page").path("nextCursor").asString();
        mvc.perform(get("/api/v1/users").param("limit","1").param("cursor",cursor).with(as("institutional|coordinator"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users").param("cursor",cursor).param("q","different").with(as("institutional|coordinator"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users?limit=101").with(as("institutional|admin"))).andExpect(status().isBadRequest());
    }
    @Test void internalEndpointsEnforceAudienceScopesWorkloadAndDelegatedSubject() throws Exception {
        mvc.perform(get("/internal/v1/users/"+user+"/status").with(as("institutional|admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/users/"+user+"/status").with(workload("identity:status")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.email").doesNotExist());
        var input=json.writeValueAsString(new Check(admin,"PROJECT_READ",new Resource("PROJECT",UUID.randomUUID(),null)));
        mvc.perform(post("/internal/v1/authorization/check").with(jwt().jwt(j->j.subject("institutional|researcher").audience(List.of("identity-service"))).authorities(new SimpleGrantedAuthority("SCOPE_identity:check")))
            .contentType("application/json").content(input)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUBJECT_MISMATCH"));
    }
    @Test void authorizationUsesOwnerMembershipAndDeniesInventedProjectAssociations() throws Exception {
        UUID project=UUID.randomUUID();
        when(owners.context(eq("project"),anyString(),anyLong())).thenReturn(json.readTree(json.writeValueAsString(Map.of(
            "projectId",project,"status","ACTIVE","classification","INTERNAL","accessVersion",7,
            "subject",Map.of("userId",user,"membershipStatus","ACTIVE","permissions",List.of("ACTIVITY_WRITE"))))));
        mvc.perform(post("/internal/v1/authorization/check").with(workload("identity:check identity:any-subject")).contentType("application/json")
            .content(json.writeValueAsString(new Check(user,"PROJECT_READ",new Resource("PROJECT",project,null)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.accessVersion").value(7));
        mvc.perform(post("/internal/v1/authorization/check").with(workload("identity:check identity:any-subject")).contentType("application/json")
            .content(json.writeValueAsString(new Check(user,"PROJECT_WRITE",new Resource("PROJECT",project,UUID.randomUUID())))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
    }
    @Test void authorizationFailuresNeverReturnPartialFiltersAndUnknownActionsDeny() throws Exception {
        when(owners.context(anyString(),anyString(),anyLong())).thenThrow(OwnerClient.unavailable());
        var resource=new Resource("PROJECT",UUID.randomUUID(),null);
        mvc.perform(post("/internal/v1/authorization/filter").with(workload("identity:filter identity:any-subject")).contentType("application/json")
            .content(json.writeValueAsString(new Filter(user,"PROJECT_READ",List.of(resource)))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.allowedResourceIds").doesNotExist());
        mvc.perform(post("/internal/v1/authorization/check").with(workload("identity:check identity:any-subject")).contentType("application/json")
            .content(json.writeValueAsString(new Check(admin,"UNKNOWN",resource))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        mvc.perform(post("/internal/v1/authorization/filter").with(workload("identity:filter identity:any-subject")).contentType("application/json")
            .content(json.writeValueAsString(new Filter(user,"PROJECT_READ",List.of(resource,resource)))))
            .andExpect(status().isBadRequest());
    }
    @Test void publisherMarksOnlyConfirmedAndRoutedEventsAndRetriesNacks() {
        events.user("UserCreated",repo.user(user),"USER",admin.toString());
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doAnswer(invocation -> {
            var correlation=invocation.getArgument(3,org.springframework.amqp.rabbit.connection.CorrelationData.class);
            correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(false,"Test nack"));
            return null;
        }).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        var publisher=new OutboxPublisher(repo,rabbit,"invesctictrack.domain");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s->publisher.publish());
        assertThat(repo.db().sql("select count(*) from outbox_event where published_at is null and attempt_count=1 and next_attempt_at is not null").query(Long.class).single()).isEqualTo(1);
        repo.db().sql("update outbox_event set next_attempt_at=null").update();
        doAnswer(invocation -> {
            var correlation=invocation.getArgument(3,org.springframework.amqp.rabbit.connection.CorrelationData.class);
            correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true,null));
            return null;
        }).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s->publisher.publish());
        assertThat(repo.db().sql("select count(*) from outbox_event where published_at is not null and attempt_count=2").query(Long.class).single()).isEqualTo(1);
    }
    @Test void publisherKeepsUnroutableConfirmedMessagesPending() {
        events.user("UserCreated",repo.user(user),"USER",admin.toString());
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doAnswer(invocation -> {
            var correlation=invocation.getArgument(3,org.springframework.amqp.rabbit.connection.CorrelationData.class);
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(invocation.getArgument(2),312,"NO_ROUTE","invesctictrack.domain","identity.UserCreated.v1"));
            correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true,null));
            return null;
        }).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        var publisher=new OutboxPublisher(repo,rabbit,"invesctictrack.domain");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s->publisher.publish());
        assertThat(repo.db().sql("select count(*) from outbox_event where published_at is null and attempt_count=1").query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentAdministratorDeactivationsCannotRemoveTheLastActiveAdministrator() throws Exception {
        UUID other=fixture("institutional|second-admin",Status.ACTIVE,"ADMIN");
        var firstActor=repo.user(admin); var secondActor=repo.user(other);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first=()->{ start.await(); try { identity.state(firstActor,admin,false,"Leaving", "\"1\""); return true; } catch(ApiException e) { assertThat(e.code).isEqualTo("LAST_ADMIN_REQUIRED"); return false; } };
            java.util.concurrent.Callable<Boolean> second=()->{ start.await(); try { identity.state(secondActor,other,false,"Leaving", "\"1\""); return true; } catch(ApiException e) { assertThat(e.code).isEqualTo("LAST_ADMIN_REQUIRED"); return false; } };
            var a=executor.submit(first); var b=executor.submit(second); start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(repo.activeAdmins()).isEqualTo(1);
        }
    }
    @Test void bootstrapNeverRestoresPrivilegesInAnExistingDatabaseAndOpenApiIsProtected() throws Exception {
        var bootstrap=new BootstrapAdministrator(repo,events,"institutional|researcher","researcher@university.test","Bootstrap");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s->bootstrap.run(new org.springframework.boot.DefaultApplicationArguments()));
        assertThat(repo.roles(user)).containsExactly("RESEARCHER");
        mvc.perform(get("/v3/api-docs").with(as("institutional|researcher"))).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs").with(jwt().jwt(j->j.audience(List.of("research-platform-api"))).authorities(new SimpleGrantedAuthority("SCOPE_monitoring"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.1.0"));
    }

    @Test void aFailedOutboxInsertRollsBackTheUserRolesHistoryAndIdempotencyRecord() throws Exception {
        repo.db().sql("alter table outbox_event add constraint test_reject_event check (event_type <> 'UserCreated')").update();
        try {
            mvc.perform(post("/api/v1/users").with(as("institutional|admin")).header("Idempotency-Key","rollback-test").contentType("application/json").content(createBody("rollback-subject","rollback@university.test")))
                .andExpect(status().isConflict());
            assertThat(repo.subject("rollback-subject")).isEmpty();
            assertThat(repo.db().sql("select count(*) from outbox_event").query(Long.class).single()).isZero();
            assertThat(repo.db().sql("select count(*) from user_status_history").query(Long.class).single()).isZero();
            assertThat(repo.db().sql("select count(*) from idempotency_record").query(Long.class).single()).isZero();
        } finally {
            repo.db().sql("alter table outbox_event drop constraint test_reject_event").update();
        }
    }

}
