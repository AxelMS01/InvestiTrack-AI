package com.example.proposalservice.api;

import com.example.proposalservice.infraestructure.persistence.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProposalApiTests {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    UUID owner=UUID.randomUUID(), coordinator=UUID.randomUUID(), evaluator=UUID.randomUUID();
    String area="AREA_"+UUID.randomUUID().toString().substring(0,8);
    String criterionCode="MERIT_"+UUID.randomUUID().toString().substring(0,8);

    @BeforeEach void catalog() {
        em.persist(new ResearchAreaEntity(area,"Engineering","Research area",owner));
        var criterion=new EvaluationCriterionEntity("Scientific merit","Merit",BigDecimal.ONE,new BigDecimal("5"),BigDecimal.ONE,LocalDate.now().minusDays(1),owner);
        criterion.setCode(criterionCode); em.persist(criterion); em.flush();
    }
    RequestPostProcessor as(UUID user,String role) { return jwt().jwt(j->j.subject("institutional-subject").claim("user_id",user.toString()).claim("roles",List.of(role))); }
    String body() { return """
        {"title":"Sensor research","abstract":"Summary","researchProblem":"Problem",
        "methodology":"Method","expectedResults":"Results","estimatedDurationMonths":12,
        "researchAreaCode":"%s","objectives":[{"type":"GENERAL","description":"Study sensors"}]}
        """.formatted(area); }
    JsonNode create() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/proposals").with(as(owner,"RESEARCHER")).contentType("application/json").content(body()))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(header().exists("ETag"))
            .andReturn().getResponse().getContentAsString());
    }
    String etag(String path,UUID user,String role) throws Exception { return mvc.perform(get(path).with(as(user,role))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag"); }
    JsonNode transition(String path,String target,UUID user,String role) throws Exception {
        return json.readTree(mvc.perform(post(path+"/transitions").with(as(user,role)).header("If-Match",etag(path,user,role))
            .contentType("application/json").content("{\"targetStatus\":\""+target+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void authenticationAndVisibilityAreEnforcedBeforePagination() throws Exception {
        mvc.perform(get("/api/v1/proposals")).andExpect(status().isUnauthorized());
        var proposal=create(); String path="/api/v1/proposals/"+proposal.get("id").asText();
        mvc.perform(get(path).with(as(UUID.randomUUID(),"RESEARCHER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/proposals").with(as(UUID.randomUUID(),"RESEARCHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/proposals").with(as(owner,"RESEARCHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/proposals?limit=101").with(as(owner,"RESEARCHER"))).andExpect(status().isBadRequest());
    }
    @Test void proposalFiltersAndDatabaseCursorsReturnOnlyTheRequestedPage() throws Exception {
        create(); create(); create();
        var first=json.readTree(mvc.perform(get("/api/v1/proposals").param("limit","1").param("sort","title").param("researchArea",area)
            .with(as(owner,"RESEARCHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.page.hasMore").value(true))
            .andReturn().getResponse().getContentAsString());
        var second=json.readTree(mvc.perform(get("/api/v1/proposals").param("limit","1").param("sort","title").param("researchArea",area)
            .param("cursor",first.get("page").get("nextCursor").asText()).with(as(owner,"RESEARCHER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNotEquals(first.get("items").get(0).get("id"),second.get("items").get(0).get("id"));
        mvc.perform(get("/api/v1/proposals").param("status","APPROVED").param("researcherId",owner.toString()).with(as(owner,"RESEARCHER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void updatesRequireCurrentVersionAndChildChangesAdvanceAggregateEtag() throws Exception {
        String path="/api/v1/proposals/"+create().get("id").asText();
        mvc.perform(patch(path).with(as(owner,"RESEARCHER")).contentType("application/json").content("{\"title\":\"Changed\"}"))
            .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        String original=etag(path,owner,"RESEARCHER");
        mvc.perform(post(path+"/researchers").with(as(owner,"RESEARCHER")).header("If-Match",original).contentType("application/json")
            .content("{\"userId\":\""+UUID.randomUUID()+"\",\"responsibility\":\"Analysis\"}"))
            .andExpect(status().isCreated());
        assertNotEquals(original,etag(path,owner,"RESEARCHER"));
        mvc.perform(patch(path).with(as(owner,"RESEARCHER")).header("If-Match",original).contentType("application/json").content("{\"title\":\"Changed\"}"))
            .andExpect(status().isPreconditionFailed());
    }
    @Test void retriesReturnOriginalResponseAndRejectDifferentBody() throws Exception {
        String key=UUID.randomUUID().toString();
        var first=mvc.perform(post("/api/v1/proposals").with(as(owner,"RESEARCHER")).header("Idempotency-Key",key).contentType("application/json").content(body()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var second=mvc.perform(post("/api/v1/proposals").with(as(owner,"RESEARCHER")).header("Idempotency-Key",key).contentType("application/json").content(body()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first),json.readTree(second));
        mvc.perform(post("/api/v1/proposals").with(as(owner,"RESEARCHER")).header("Idempotency-Key",key).contentType("application/json").content(body().replace("Sensor research","Different title")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void draftCancellationAndDeletionRespectLifecycle() throws Exception {
        String path="/api/v1/proposals/"+create().get("id").asText();
        transition(path,"CANCELLED",owner,"RESEARCHER");
        mvc.perform(post(path+"/transitions").with(as(owner,"RESEARCHER")).header("If-Match",etag(path,owner,"RESEARCHER")).contentType("application/json").content("{\"targetStatus\":\"SUBMITTED\"}"))
            .andExpect(status().isConflict());
        String draft="/api/v1/proposals/"+create().get("id").asText();
        mvc.perform(delete(draft).with(as(owner,"RESEARCHER")).header("If-Match",etag(draft,owner,"RESEARCHER"))).andExpect(status().isNoContent());
        mvc.perform(get(draft).with(as(owner,"RESEARCHER"))).andExpect(status().isNotFound());
    }
    @Test void resubmissionRequiresFreshEvaluationAssignments() throws Exception {
        String path="/api/v1/proposals/"+create().get("id").asText();
        transition(path,"SUBMITTED",owner,"RESEARCHER");
        String assignmentBody="{\"evaluatorId\":\""+evaluator+"\"}";
        mvc.perform(post(path+"/evaluator-assignments").with(as(coordinator,"COORDINATOR")).header("If-Match",etag(path,coordinator,"COORDINATOR"))
            .contentType("application/json").content(assignmentBody)).andExpect(status().isCreated());
        transition(path,"UNDER_REVIEW",coordinator,"COORDINATOR");
        var evaluation=json.readTree(mvc.perform(post(path+"/evaluations").with(as(evaluator,"EVALUATOR")).contentType("application/json")
            .content("{\"criteria\":[{\"criterionCode\":\""+criterionCode+"\",\"score\":4}],\"decision\":\"REQUEST_CHANGES\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String evaluationPath=path+"/evaluations/"+evaluation.get("id").asText();
        mvc.perform(post(evaluationPath+"/submission").with(as(evaluator,"EVALUATOR")).header("If-Match",etag(evaluationPath,evaluator,"EVALUATOR"))).andExpect(status().isOk());
        transition(path,"CHANGES_REQUESTED",coordinator,"COORDINATOR");
        transition(path,"SUBMITTED",owner,"RESEARCHER");
        mvc.perform(post(path+"/transitions").with(as(coordinator,"COORDINATOR")).header("If-Match",etag(path,coordinator,"COORDINATOR"))
            .contentType("application/json").content("{\"targetStatus\":\"UNDER_REVIEW\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVALUATOR_REQUIRED"));
        mvc.perform(post(path+"/evaluator-assignments").with(as(coordinator,"COORDINATOR")).header("If-Match",etag(path,coordinator,"COORDINATOR"))
            .contentType("application/json").content(assignmentBody)).andExpect(status().isCreated());
        transition(path,"UNDER_REVIEW",coordinator,"COORDINATOR");
    }

    @Test void evaluationWorkflowReleasesOnlySubmittedResultsAndPersistsApprovalSnapshot() throws Exception {
        String path="/api/v1/proposals/"+create().get("id").asText();
        transition(path,"SUBMITTED",owner,"RESEARCHER");
        var assignment=json.readTree(mvc.perform(post(path+"/evaluator-assignments").with(as(coordinator,"COORDINATOR"))
            .header("If-Match",etag(path,coordinator,"COORDINATOR")).contentType("application/json").content("{\"evaluatorId\":\""+evaluator+"\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        transition(path,"UNDER_REVIEW",coordinator,"COORDINATOR");
        String criteria="{\"criteria\":[{\"criterionCode\":\""+criterionCode+"\",\"score\":4}],\"decision\":\"APPROVE\"}";
        var evaluation=json.readTree(mvc.perform(post(path+"/evaluations").with(as(evaluator,"EVALUATOR")).contentType("application/json").content(criteria))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String evaluationPath=path+"/evaluations/"+evaluation.get("id").asText();
        mvc.perform(get(evaluationPath).with(as(owner,"RESEARCHER"))).andExpect(status().isNotFound());
        mvc.perform(post(path+"/transitions").with(as(coordinator,"COORDINATOR")).header("If-Match",etag(path,coordinator,"COORDINATOR")).contentType("application/json").content("{\"targetStatus\":\"APPROVED\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post(evaluationPath+"/submission").with(as(evaluator,"EVALUATOR")).header("If-Match",etag(evaluationPath,evaluator,"EVALUATOR")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(patch(evaluationPath).with(as(evaluator,"EVALUATOR")).header("If-Match",etag(evaluationPath,evaluator,"EVALUATOR")).contentType("application/json").content(criteria)).andExpect(status().isConflict());
        mvc.perform(post(evaluationPath+"/release").with(as(coordinator,"COORDINATOR")).header("If-Match",etag(evaluationPath,coordinator,"COORDINATOR"))).andExpect(status().isOk());
        mvc.perform(get(evaluationPath).with(as(owner,"RESEARCHER"))).andExpect(status().isOk());
        mvc.perform(get(evaluationPath+"/history").with(as(owner,"RESEARCHER"))).andExpect(status().isForbidden());
        transition(path,"APPROVED",coordinator,"COORDINATOR");
        String internal="/internal/v1/proposals/"+path.substring(path.lastIndexOf('/')+1);
        mvc.perform(get(internal+"/project-creation-data").with(as(owner,"RESEARCHER"))).andExpect(status().isForbidden());
        var service=jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_proposal:project-integration"));
        var snapshot=json.readTree(mvc.perform(get(internal+"/project-creation-data").with(service)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String reference="{\"approvalId\":\""+snapshot.get("approvalId").asText()+"\",\"projectId\":\""+UUID.randomUUID()+"\"}";
        for(int i=0;i<2;i++) mvc.perform(put(internal+"/project-reference").with(service).contentType("application/json").content(reference)).andExpect(status().isNoContent());
        mvc.perform(put(internal+"/project-reference").with(service).contentType("application/json").content(reference.replace(json.readTree(reference).get("projectId").asText(),UUID.randomUUID().toString()))).andExpect(status().isConflict());
        assertTrue(em.createQuery("select count(e) from OutboxEventEntity e",Long.class).getSingleResult()>0);
    }
}
