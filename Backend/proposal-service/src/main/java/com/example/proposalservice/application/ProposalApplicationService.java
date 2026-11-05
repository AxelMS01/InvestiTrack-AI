package com.example.proposalservice.application;

import com.example.proposalservice.api.*;
import com.example.proposalservice.api.ApiModels.*;
import com.example.proposalservice.infraestructure.persistence.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.example.proposalservice.api.ApiException.require;

@Service
@Transactional
public class ProposalApplicationService {
    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;
    public ProposalApplicationService(ObjectMapper json) { this.json=json; }

    public static Map<String,Object> fields(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    private <T> T find(Class<T> type,Object id) {
        T value=em.find(type,id); require(value!=null,404,"NOT_FOUND","Resource was not found."); return value;
    }
    private <T> List<T> children(Class<T> type,String relation,UUID id) {
        return em.createQuery("select e from "+type.getSimpleName()+" e where e."+relation+".id = :id",type).setParameter("id",id).getResultList();
    }
    private List<ProposalResearcherEntity> researchers(ProposalEntity p) { return children(ProposalResearcherEntity.class,"proposal",p.getId()); }
    private List<EvaluatorAssignmentEntity> currentAssignments(ProposalEntity p) {
        return children(EvaluatorAssignmentEntity.class,"proposal",p.getId()).stream()
            .filter(a->p.getSubmittedAt()==null || !a.getAssignedAt().isBefore(p.getSubmittedAt())).toList();
    }
    private boolean participant(ProposalEntity p,Caller c) {
        return p.getOwnerUserId().equals(c.id()) || researchers(p).stream().anyMatch(r->r.getId().getUserId().equals(c.id()));
    }
    private boolean assigned(ProposalEntity p,Caller c) {
        return children(EvaluatorAssignmentEntity.class,"proposal",p.getId()).stream()
            .anyMatch(a->a.getEvaluatorUserId().equals(c.id()) && a.getStatus()!=EvaluatorAssignmentStatus.REVOKED && a.getStatus()!=EvaluatorAssignmentStatus.DECLINED);
    }
    private boolean visible(ProposalEntity p,Caller c) { return c.coordinator() || participant(p,c) || assigned(p,c); }
    private ProposalEntity proposal(UUID id,Caller c) {
        var p=find(ProposalEntity.class,id); require(visible(p,c),404,"NOT_FOUND","Proposal was not found."); return p;
    }
    private void owner(ProposalEntity p,Caller c) { require(p.getOwnerUserId().equals(c.id()),403,"FORBIDDEN","Proposal owner is required."); }
    private void editable(ProposalEntity p) { require(p.getStatus()==ProposalStatus.DRAFT || p.getStatus()==ProposalStatus.CHANGES_REQUESTED,409,"PROPOSAL_NOT_EDITABLE","Proposal is not editable in its current status."); }
    public static void match(String header,Long version) {
        require(header!=null,428,"IF_MATCH_REQUIRED","Supply the current ETag in If-Match.");
        require(header.matches("\"[0-9]+\""),400,"INVALID_ETAG","If-Match must contain one quoted version.");
        require(header.equals("\""+version+"\""),412,"STALE_VERSION","The resource version is stale.");
    }
    private void touch(ProposalEntity p,Caller c) {
        p.markChanged(c.id());
    }
    private void event(String type,UUID id,String event,Caller c,Map<String,Object> data) {
        UUID eventId=UUID.randomUUID(); Instant now=Instant.now();
        var envelope=fields("eventId",eventId,"eventType",event,"eventVersion",1,"occurredAt",now,
            "producer","proposal-service","correlationId",eventId.toString(),"actor",fields("type","USER","id",c.id()),"data",data);
        var record=new OutboxEventEntity(type,id,event,1,json.writeValueAsString(envelope),now);
        record.setActorId(c.id().toString()); record.setCorrelationId(eventId.toString()); em.persist(record);
        em.persist(new ApiRecord("history:"+id+":"+eventId,"history",json.writeValueAsString(envelope)));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> replay(Caller c,String operation,String key,Object body,Supplier<Map<String,Object>> action) {
        if(key==null) return action.get();
        require(!key.isBlank() && key.length()<=200,400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key must contain 1 to 200 characters.");
        String scope=hash(c.id()+":"+operation+":"+key);
        // Serialize concurrent retries before checking/inserting the durable response.
        em.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(:scope, 0))",Object.class).setParameter("scope",scope).getResultList();
        String digest=hash(json.writeValueAsString(body));
        var existing=em.find(ApiRecord.class,"request:"+scope);
        if(existing!=null) {
            require(existing.getRequestHash().equals(digest),409,"IDEMPOTENCY_CONFLICT","This key was used with a different request.");
            return json.readValue(existing.getPayload(),LinkedHashMap.class);
        }
        var result=action.get(); em.persist(new ApiRecord("request:"+scope,digest,json.writeValueAsString(result))); return result;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Map<String,Object> create(ProposalInput input,Caller c,String key) {
        require(c.researcher(),403,"FORBIDDEN","Researcher role is required.");
        return replay(c,"create-proposal",key,input,()->{
            require(input.title()!=null && !input.title().isBlank(),422,"TITLE_REQUIRED","A proposal title is required.");
            var p=new ProposalEntity(null,input.title(),c.id(),c.id()); em.persist(p); em.flush();
            apply(p,input,c);
            if(input.researcherIds()==null) em.persist(new ProposalResearcherEntity(p,c.id(),"PRINCIPAL",Instant.now(),c.id()));
            var h=new ProposalStatusHistoryEntity(p,ProposalStatus.DRAFT,Instant.now(),c.id()); em.persist(h);
            em.flush(); var result=proposalView(p); event("Proposal",p.getId(),"ProposalCreated",c,result); return result;
        });
    }
    public Map<String,Object> get(UUID id,Caller c) { return proposalView(proposal(id,c)); }
    public Map<String,Object> update(UUID id,ProposalInput input,Caller c,String etag) {
        var p=proposal(id,c); require(participant(p,c),403,"FORBIDDEN","An authorized researcher is required."); editable(p); match(etag,p.getVersion());
        apply(p,input,c); touch(p,c); em.flush(); var result=proposalView(p); event("Proposal",id,"ProposalUpdated",c,result); return result;
    }
    private void apply(ProposalEntity p,ProposalInput input,Caller c) {
        if(input.title()!=null) { require(!input.title().isBlank(),422,"TITLE_REQUIRED","Title cannot be blank."); p.setTitle(input.title()); }
        if(input.abstractText()!=null) p.setAbstractText(input.abstractText());
        if(input.researchProblem()!=null) p.setResearchProblem(input.researchProblem());
        if(input.methodology()!=null) p.setMethodology(input.methodology());
        if(input.expectedResults()!=null) p.setExpectedResults(input.expectedResults());
        if(input.estimatedDurationMonths()!=null) p.setEstimatedDurationMonths(input.estimatedDurationMonths());
        if(input.researchAreaCode()!=null) {
            var areas=em.createQuery("select a from ResearchAreaEntity a where a.code=:code and a.active=true",ResearchAreaEntity.class).setParameter("code",input.researchAreaCode()).getResultList();
            require(!areas.isEmpty(),422,"INVALID_RESEARCH_AREA","An active research area is required."); p.setResearchArea(areas.getFirst());
        }
        if(input.objectives()!=null) {
            children(ProposalObjectiveEntity.class,"proposal",p.getId()).forEach(em::remove); em.flush();
            int order=0; for(var o:input.objectives()) em.persist(new ProposalObjectiveEntity(p,o.type(),o.description(),order++,c.id()));
        }
        if(input.researcherIds()!=null) {
            owner(p,c); unique(input.researcherIds());
            require(children(EvaluatorAssignmentEntity.class,"proposal",p.getId()).stream().noneMatch(a->input.researcherIds().contains(a.getEvaluatorUserId()) && a.getStatus()!=EvaluatorAssignmentStatus.REVOKED && a.getStatus()!=EvaluatorAssignmentStatus.DECLINED),409,"EVALUATOR_CONFLICT","An assigned evaluator cannot become a proposal researcher."); researchers(p).forEach(em::remove); em.flush();
            var ids=new LinkedHashSet<>(input.researcherIds()); ids.add(p.getOwnerUserId());
            for(var user:ids) em.persist(new ProposalResearcherEntity(p,user,user.equals(p.getOwnerUserId())?"PRINCIPAL":"RESEARCHER",Instant.now(),c.id()));
        }
        if(input.supportingDocumentIds()!=null) {
            unique(input.supportingDocumentIds()); children(ProposalDocumentReferenceEntity.class,"proposal",p.getId()).forEach(em::remove); em.flush();
            for(var doc:input.supportingDocumentIds()) em.persist(new ProposalDocumentReferenceEntity(p,doc,"SUPPORTING",Instant.now(),c.id()));
        }
    }
    private static void unique(List<?> values) { require(new HashSet<>(values).size()==values.size(),422,"DUPLICATE_VALUES","Lists cannot contain duplicate values."); }
    public Map<String,Object> proposalView(ProposalEntity p) {
        var objectives=children(ProposalObjectiveEntity.class,"proposal",p.getId()).stream().sorted(Comparator.comparing(ProposalObjectiveEntity::getDisplayOrder))
            .map(o->fields("type",o.getObjectiveType(),"description",o.getDescription())).toList();
        return fields("id",p.getId(),"title",p.getTitle(),"abstract",p.getAbstractText(),"researchProblem",p.getResearchProblem(),
            "methodology",p.getMethodology(),"expectedResults",p.getExpectedResults(),"researchAreaCode",p.getResearchArea()==null?null:p.getResearchArea().getCode(),
            "estimatedDurationMonths",p.getEstimatedDurationMonths(),"status",p.getStatus(),"ownerUserId",p.getOwnerUserId(),
            "objectives",objectives,"researcherIds",researchers(p).stream().map(r->r.getId().getUserId()).toList(),
            "supportingDocumentIds",children(ProposalDocumentReferenceEntity.class,"proposal",p.getId()).stream().map(ProposalDocumentReferenceEntity::getDocumentId).toList(),
            "submittedAt",p.getSubmittedAt(),"createdAt",p.getCreatedAt(),"updatedAt",p.getUpdatedAt(),"version",p.getVersion());
    }
    public void delete(UUID id,Caller c,String etag) {
        var p=find(ProposalEntity.class,id); require(p.getOwnerUserId().equals(c.id()) || c.administrator(),403,"FORBIDDEN","Owner or administrator is required.");
        match(etag,p.getVersion()); require(p.getStatus()==ProposalStatus.DRAFT,409,"DRAFT_REQUIRED","Only drafts may be deleted.");
        // Keep an audit tombstone and outbox record; delete only the draft aggregate data.
        event("Proposal",id,"ProposalDeleted",c,fields("proposalId",id));
        for(var type:List.of(ProposalObjectiveEntity.class,ProposalDocumentReferenceEntity.class,ProposalResearcherEntity.class,ProposalStatusHistoryEntity.class))
            children(type,"proposal",id).forEach(em::remove);
        em.remove(p); em.flush();
    }
    public static boolean allowed(ProposalStatus from,ProposalStatus to) {
        return switch(from) {
            case DRAFT,CHANGES_REQUESTED -> to==ProposalStatus.SUBMITTED || to==ProposalStatus.CANCELLED;
            case SUBMITTED -> to==ProposalStatus.UNDER_REVIEW || to==ProposalStatus.CANCELLED;
            case UNDER_REVIEW -> Set.of(ProposalStatus.CHANGES_REQUESTED,ProposalStatus.APPROVED,ProposalStatus.REJECTED).contains(to);
            default -> false;
        };
    }
    public Map<String,Object> transition(UUID id,Transition input,Caller c,String etag) {
        var p=proposal(id,c); match(etag,p.getVersion()); var from=p.getStatus(); var to=input.targetStatus();
        require(allowed(from,to),409,"PROPOSAL_TRANSITION_NOT_ALLOWED","Transition from "+from+" to "+to+" is not allowed.");
        if(from==ProposalStatus.DRAFT || from==ProposalStatus.CHANGES_REQUESTED) require(participant(p,c),403,"FORBIDDEN","An authorized researcher is required.");
        else c.requireCoordinator();
        if(to==ProposalStatus.SUBMITTED) {
            require(p.getResearchArea()!=null && p.getAbstractText()!=null && !p.getAbstractText().isBlank()
                && p.getResearchProblem()!=null && !p.getResearchProblem().isBlank() && p.getMethodology()!=null && !p.getMethodology().isBlank()
                && p.getExpectedResults()!=null && !p.getExpectedResults().isBlank() && p.getEstimatedDurationMonths()!=null
                && children(ProposalObjectiveEntity.class,"proposal",id).stream().anyMatch(o->o.getObjectiveType()==ObjectiveType.GENERAL),
                422,"INCOMPLETE_PROPOSAL","Complete the research area, abstract, problem, methodology, expected results, duration and general objective before submission.");
            p.setSubmittedAt(Instant.now()); p.setSubmittedBy(c.id());
        }
        var assignments=currentAssignments(p);
        if(to==ProposalStatus.UNDER_REVIEW) require(assignments.stream().anyMatch(a->a.getStatus()==EvaluatorAssignmentStatus.ASSIGNED || a.getStatus()==EvaluatorAssignmentStatus.ACCEPTED),409,"EVALUATOR_REQUIRED","Assign an evaluator before starting review.");
        if(from==ProposalStatus.UNDER_REVIEW) {
            var active=assignments.stream().filter(a->a.getStatus()!=EvaluatorAssignmentStatus.REVOKED && a.getStatus()!=EvaluatorAssignmentStatus.DECLINED).toList();
            require(!active.isEmpty() && active.stream().allMatch(a->a.getStatus()==EvaluatorAssignmentStatus.COMPLETED),409,"EVALUATIONS_INCOMPLETE","All active evaluator assignments must be completed before a decision.");
            var decision=new ProposalDecisionEntity(p,ProposalDecision.valueOf(to.name()),Instant.now(),c.id()); decision.setRationale(input.reason()); em.persist(decision); em.flush();
            if(to==ProposalStatus.APPROVED) {
                var snapshot=new LinkedHashMap<>(proposalView(p)); snapshot.put("proposalVersion",p.getVersion()+1); snapshot.put("version",p.getVersion()+1); snapshot.put("approvalId",decision.getId()); snapshot.put("approvedAt",decision.getDecidedAt()); snapshot.put("status",to); snapshot.put("proposalId",id);
                em.persist(new ApiRecord("approval:"+id,"approved",json.writeValueAsString(snapshot)));
            }
        }
        if(to==ProposalStatus.CANCELLED) p.setCancelledAt(Instant.now());
        p.setStatus(to); touch(p,c);
        var h=new ProposalStatusHistoryEntity(p,to,Instant.now(),c.id()); h.setFromStatus(from); h.setReason(input.reason()); em.persist(h); em.flush();
        var result=proposalView(p); String name=switch(to) { case SUBMITTED->"ProposalSubmitted"; case APPROVED->"ProposalApproved"; case REJECTED->"ProposalRejected"; case CHANGES_REQUESTED->"ProposalChangesRequested"; default->"ProposalStatusChanged"; };
        event("Proposal",id,name,c,result); return result;
    }
    public List<Map<String,Object>> history(UUID id,Caller c,boolean evaluation) {
        if(evaluation) require(c.coordinator() || c.auditor(),403,"FORBIDDEN","Coordinator or auditor is required."); else proposal(id,c);
        return em.createQuery("select r from ApiRecord r where r.id like :prefix order by r.id",ApiRecord.class)
            .setParameter("prefix","history:"+id+":%").getResultList().stream().map(r->{ var entry=read(r.getPayload()); entry.put("id",entry.get("eventId")); return entry; }).filter(entry->evaluation || c.coordinator() || !entry.get("eventType").toString().startsWith("Evaluator")).sorted(Comparator.comparing(m->m.get("occurredAt").toString())).toList();
    }
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value) { return json.readValue(value,LinkedHashMap.class); }

    public List<Map<String,Object>> listResearchers(UUID id,Caller c) { return researchers(proposal(id,c)).stream().map(this::researcherView).toList(); }
    private Map<String,Object> researcherView(ProposalResearcherEntity r) { return fields("id",r.getId().getUserId(),"userId",r.getId().getUserId(),"proposalRole",r.getProposalRole(),"responsibility",r.getResponsibility()); }
    public Map<String,Object> addResearcher(UUID id,ResearcherInput input,Caller c,String etag,String key) {
        return replay(c,"researcher:"+id,key,input,()->saveResearcher(id,null,input,c,etag,true));
    }
    public Map<String,Object> saveResearcher(UUID id,UUID user,ResearcherInput input,Caller c,String etag,boolean create) {
        var p=proposal(id,c); owner(p,c); editable(p); match(etag,p.getVersion());
        UUID userId=create?input.userId():user; require(userId!=null,422,"USER_REQUIRED","userId is required.");
        require(children(EvaluatorAssignmentEntity.class,"proposal",id).stream().noneMatch(a->a.getEvaluatorUserId().equals(userId) && a.getStatus()!=EvaluatorAssignmentStatus.REVOKED && a.getStatus()!=EvaluatorAssignmentStatus.DECLINED),409,"EVALUATOR_CONFLICT","An assigned evaluator cannot become a proposal researcher.");
        var key=new ProposalResearcherId(id,userId); var r=em.find(ProposalResearcherEntity.class,key);
        if(create) { require(r==null,409,"RESEARCHER_EXISTS","Researcher is already a collaborator."); r=new ProposalResearcherEntity(p,userId,input.proposalRole()==null?"RESEARCHER":input.proposalRole(),Instant.now(),c.id()); em.persist(r); }
        else { require(r!=null,404,"NOT_FOUND","Researcher was not found."); if(input.proposalRole()!=null) r.setProposalRole(input.proposalRole()); }
        require(!r.getProposalRole().isBlank(),422,"INVALID_ROLE","Proposal role cannot be blank.");
        r.setResponsibility(input.responsibility()); touch(p,c); em.flush(); var result=researcherView(r); event("Proposal",id,"ProposalResearcherChanged",c,result); result.put("version",p.getVersion()); return result;
    }
    public void deleteResearcher(UUID id,UUID user,Caller c,String etag) {
        var p=proposal(id,c); owner(p,c); editable(p); match(etag,p.getVersion()); require(!p.getOwnerUserId().equals(user),409,"OWNER_REQUIRED","The proposal owner cannot be removed.");
        em.remove(find(ProposalResearcherEntity.class,new ProposalResearcherId(id,user))); touch(p,c); event("Proposal",id,"ProposalResearcherRemoved",c,fields("userId",user)); em.flush();
    }
    public List<Map<String,Object>> assignments(UUID id,Caller c) {
        var p=proposal(id,c); require(c.coordinator() || assigned(p,c),403,"FORBIDDEN","Coordinator or assigned evaluator is required."); return children(EvaluatorAssignmentEntity.class,"proposal",id).stream()
            .filter(a->c.coordinator() || a.getEvaluatorUserId().equals(c.id())).map(this::assignmentView).toList();
    }
    private EvaluatorAssignmentEntity assignment(UUID id,UUID assignment,Caller c) {
        proposal(id,c); var a=find(EvaluatorAssignmentEntity.class,assignment);
        require(a.getProposal().getId().equals(id),404,"NOT_FOUND","Assignment was not found.");
        require(c.coordinator() || a.getEvaluatorUserId().equals(c.id()),403,"FORBIDDEN","Assignment is not visible."); return a;
    }
    private Map<String,Object> assignmentView(EvaluatorAssignmentEntity a) { return fields("id",a.getId(),"proposalId",a.getProposal().getId(),"evaluatorId",a.getEvaluatorUserId(),"status",a.getStatus(),"dueDate",a.getDueDate(),"instructions",a.getInstructions(),"assignedAt",a.getAssignedAt(),"version",a.getVersion()); }
    public Map<String,Object> getAssignment(UUID id,UUID aid,Caller c) { return assignmentView(assignment(id,aid,c)); }
    public Map<String,Object> createAssignment(UUID id,AssignmentInput input,Caller c,String etag,String key) {
        c.requireCoordinator(); return replay(c,"assignment:"+id,key,input,()->{
            var p=proposal(id,c); match(etag,p.getVersion()); require(p.getStatus()==ProposalStatus.SUBMITTED || p.getStatus()==ProposalStatus.UNDER_REVIEW,409,"ASSIGNMENT_NOT_ALLOWED","Assignments require a submitted proposal or a proposal under review.");
            require(input.evaluatorId()!=null,422,"EVALUATOR_REQUIRED","evaluatorId is required.");
            require(!p.getOwnerUserId().equals(input.evaluatorId()) && researchers(p).stream().noneMatch(r->r.getId().getUserId().equals(input.evaluatorId())),409,"EVALUATOR_CONFLICT","A proposal researcher cannot evaluate their own proposal.");
            require(currentAssignments(p).stream().noneMatch(a->a.getEvaluatorUserId().equals(input.evaluatorId()) && a.getStatus()!=EvaluatorAssignmentStatus.REVOKED && a.getStatus()!=EvaluatorAssignmentStatus.DECLINED),409,"ASSIGNMENT_EXISTS","Evaluator already has an assignment.");
            var a=new EvaluatorAssignmentEntity(p,input.evaluatorId(),EvaluatorAssignmentStatus.ASSIGNED,Instant.now(),c.id(),c.id()); a.setDueDate(input.dueDate()); a.setInstructions(input.instructions()); em.persist(a); touch(p,c); em.flush();
            var result=assignmentView(a); event("Proposal",id,"EvaluatorAssigned",c,result); return result;
        });
    }
    public Map<String,Object> updateAssignment(UUID id,UUID aid,AssignmentInput input,Caller c,String etag) {
        c.requireCoordinator(); var a=assignment(id,aid,c); match(etag,a.getVersion());
        require(a.getStatus()==EvaluatorAssignmentStatus.ASSIGNED || a.getStatus()==EvaluatorAssignmentStatus.ACCEPTED,409,"ASSIGNMENT_LOCKED","Only pending assignments can be changed.");
        require(input.evaluatorId()==null || input.evaluatorId().equals(a.getEvaluatorUserId()),422,"EVALUATOR_IMMUTABLE","Evaluator cannot be changed.");
        if(input.dueDate()!=null) a.setDueDate(input.dueDate()); if(input.instructions()!=null) a.setInstructions(input.instructions()); a.markChanged(c.id()); em.flush();
        var result=assignmentView(a); event("Proposal",id,"EvaluatorAssignmentUpdated",c,result); return result;
    }
    public void revoke(UUID id,UUID aid,Caller c,String etag) {
        c.requireCoordinator(); var a=assignment(id,aid,c); match(etag,a.getVersion()); require(a.getStatus()!=EvaluatorAssignmentStatus.COMPLETED && a.getStatus()!=EvaluatorAssignmentStatus.REVOKED,409,"ASSIGNMENT_LOCKED","Assignment cannot be revoked.");
        a.setStatus(EvaluatorAssignmentStatus.REVOKED); a.setRevokedAt(Instant.now()); a.setRevokedBy(c.id()); a.markChanged(c.id()); touch(a.getProposal(),c); event("Proposal",id,"EvaluatorAssignmentRevoked",c,fields("assignmentId",aid)); em.flush();
    }
    private boolean evaluationVisible(EvaluationEntity e,Caller c) {
        return c.coordinator() || (e.getEvaluatorUserId().equals(c.id()) && assigned(e.getProposal(),c)) || (e.getStatus()==EvaluationStatus.RELEASED && participant(e.getProposal(),c));
    }
    private EvaluationEntity evaluation(UUID id,UUID eid,Caller c) {
        var e=find(EvaluationEntity.class,eid); require(e.getProposal().getId().equals(id) && evaluationVisible(e,c),404,"NOT_FOUND","Evaluation was not found."); return e;
    }
    private Map<String,Object> evaluationView(EvaluationEntity e) {
        return fields("id",e.getId(),"proposalId",e.getProposal().getId(),"assignmentId",e.getAssignment().getId(),"evaluatorId",e.getEvaluatorUserId(),"status",e.getStatus(),
            "criteria",children(EvaluationScoreEntity.class,"evaluation",e.getId()).stream().map(s->fields("criterionCode",s.getCriterionCodeSnapshot(),"score",s.getScore(),"comment",s.getComment())).toList(),
            "observations",e.getObservations(),"recommendations",e.getRecommendations(),"decision",e.getDecision(),"totalScore",e.getTotalScore(),"submittedAt",e.getSubmittedAt(),"releasedAt",e.getReleasedAt(),"version",e.getVersion());
    }
    public List<Map<String,Object>> evaluations(UUID id,Caller c) { proposal(id,c); return children(EvaluationEntity.class,"proposal",id).stream().filter(e->evaluationVisible(e,c)).map(this::evaluationView).toList(); }
    public Map<String,Object> getEvaluation(UUID id,UUID eid,Caller c) { return evaluationView(evaluation(id,eid,c)); }
    public Map<String,Object> createEvaluation(UUID id,EvaluationInput input,Caller c,String key) {
        return replay(c,"evaluation:"+id,key,input,()->{
            var p=proposal(id,c); require(p.getStatus()==ProposalStatus.UNDER_REVIEW,409,"REVIEW_REQUIRED","Proposal must be under review.");
            var assignments=children(EvaluatorAssignmentEntity.class,"proposal",id).stream().filter(a->a.getEvaluatorUserId().equals(c.id()) && (a.getStatus()==EvaluatorAssignmentStatus.ASSIGNED || a.getStatus()==EvaluatorAssignmentStatus.ACCEPTED)).toList();
            require(assignments.size()==1,403,"ASSIGNMENT_REQUIRED","An active evaluator assignment is required.");
            var a=assignments.getFirst(); em.lock(a,LockModeType.PESSIMISTIC_WRITE);
            require(children(EvaluationEntity.class,"proposal",id).stream().noneMatch(e->e.getAssignment().getId().equals(a.getId())),409,"EVALUATION_EXISTS","An evaluation already exists for this assignment.");
            var e=new EvaluationEntity(a,p,c.id(),EvaluationStatus.DRAFT,c.id()); em.persist(e); em.flush(); applyEvaluation(e,input,c); em.flush();
            var result=evaluationView(e); event("Evaluation",e.getId(),"EvaluationCreated",c,result); return result;
        });
    }
    private void evaluationOwner(EvaluationEntity e,Caller c) {
        require(e.getEvaluatorUserId().equals(c.id()),403,"FORBIDDEN","Owning evaluator is required.");
        require(e.getStatus()==EvaluationStatus.DRAFT,409,"EVALUATION_LOCKED","Only draft evaluations can be changed.");
        require(e.getProposal().getStatus()==ProposalStatus.UNDER_REVIEW && (e.getAssignment().getStatus()==EvaluatorAssignmentStatus.ASSIGNED || e.getAssignment().getStatus()==EvaluatorAssignmentStatus.ACCEPTED),409,"ASSIGNMENT_INACTIVE","Evaluation requires an active assignment and proposal review.");
    }
    public Map<String,Object> updateEvaluation(UUID id,UUID eid,EvaluationInput input,Caller c,String etag) {
        var e=evaluation(id,eid,c); evaluationOwner(e,c); match(etag,e.getVersion()); applyEvaluation(e,input,c); em.flush();
        var result=evaluationView(e); event("Evaluation",eid,"EvaluationUpdated",c,result); return result;
    }
    private void applyEvaluation(EvaluationEntity e,EvaluationInput input,Caller c) {
        if(input.observations()!=null) e.setObservations(input.observations()); if(input.recommendations()!=null) e.setRecommendations(input.recommendations()); if(input.decision()!=null) e.setDecision(input.decision());
        if(input.criteria()!=null) {
            unique(input.criteria().stream().map(Score::criterionCode).toList());
            children(EvaluationScoreEntity.class,"evaluation",e.getId()).forEach(em::remove); em.flush(); BigDecimal total=BigDecimal.ZERO;
            for(var score:input.criteria()) {
                var criterion=criterion(score.criterionCode()); require(active(criterion),422,"INACTIVE_CRITERION","Criterion is not active.");
                require(score.score().compareTo(criterion.getMinimumScore())>=0 && score.score().compareTo(criterion.getMaximumScore())<=0,422,"SCORE_OUT_OF_RANGE","Score is outside the criterion range.");
                var s=new EvaluationScoreEntity(e,criterion,criterion.getCode(),criterion.getName(),criterion.getWeight(),score.score(),c.id()); s.setComment(score.comment()); em.persist(s);
                total=total.add(score.score().multiply(criterion.getWeight()));
            }
            e.setTotalScore(total.setScale(2,RoundingMode.HALF_UP));
        }
        e.markChanged(c.id());
    }
    public Map<String,Object> submit(UUID id,UUID eid,Caller c,String etag) {
        var e=evaluation(id,eid,c); evaluationOwner(e,c); match(etag,e.getVersion());
        var scores=children(EvaluationScoreEntity.class,"evaluation",eid);
        var required=activeCriteria();
        require(e.getDecision()!=null && !scores.isEmpty() && required.stream().allMatch(k->scores.stream().anyMatch(s->s.getCriterion().getId().equals(k.getId()))),422,"INCOMPLETE_EVALUATION","A decision and scores for every active criterion are required.");
        e.setStatus(EvaluationStatus.SUBMITTED); e.setSubmittedAt(Instant.now()); e.markChanged(c.id());
        var a=e.getAssignment(); a.setStatus(EvaluatorAssignmentStatus.COMPLETED); a.setCompletedAt(Instant.now()); a.markChanged(c.id()); touch(e.getProposal(),c); em.flush();
        var result=evaluationView(e); event("Evaluation",eid,"EvaluationSubmitted",c,result); return result;
    }
    public Map<String,Object> release(UUID id,UUID eid,Caller c,String etag) {
        c.requireCoordinator(); var e=evaluation(id,eid,c); match(etag,e.getVersion()); require(e.getStatus()==EvaluationStatus.SUBMITTED,409,"SUBMISSION_REQUIRED","Only submitted evaluations can be released.");
        e.setStatus(EvaluationStatus.RELEASED); e.setReleasedAt(Instant.now()); e.setReleasedBy(c.id()); e.markChanged(c.id()); em.flush();
        var result=evaluationView(e); event("Evaluation",eid,"EvaluationReleased",c,result); return result;
    }
    public List<Map<String,Object>> evaluationHistory(UUID id,UUID eid,Caller c) {
        require(c.coordinator() || c.auditor(),403,"FORBIDDEN","Coordinator or auditor is required.");
        var e=find(EvaluationEntity.class,eid); require(e.getProposal().getId().equals(id),404,"NOT_FOUND","Evaluation was not found."); return history(eid,c,true);
    }
    private boolean active(EvaluationCriterionEntity c) { var today=LocalDate.now(ZoneOffset.UTC); return !c.getActiveFrom().isAfter(today) && (c.getActiveUntil()==null || !c.getActiveUntil().isBefore(today)); }
    private List<EvaluationCriterionEntity> activeCriteria() { return em.createQuery("select c from EvaluationCriterionEntity c order by c.code",EvaluationCriterionEntity.class).getResultList().stream().filter(this::active).toList(); }
    private EvaluationCriterionEntity criterion(String code) {
        var found=em.createQuery("select c from EvaluationCriterionEntity c where c.code=:code",EvaluationCriterionEntity.class).setParameter("code",code).getResultList();
        require(!found.isEmpty(),422,"UNKNOWN_CRITERION","Unknown criterion code."); return found.getFirst();
    }
    private void catalogAccess(Caller c) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var jwt=((org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)auth).getToken();
        var roles=jwt.getClaimAsStringList("roles"); require(c.coordinator() || roles!=null && roles.contains("EVALUATOR"),403,"FORBIDDEN","Evaluator or coordinator is required.");
    }
    private Map<String,Object> criterionView(EvaluationCriterionEntity c) { return fields("id",c.getId(),"code",c.getCode(),"name",c.getName(),"description",c.getDescription(),"minimumScore",c.getMinimumScore(),"maximumScore",c.getMaximumScore(),"weight",c.getWeight(),"activeFrom",c.getActiveFrom(),"activeUntil",c.getActiveUntil()); }
    public List<Map<String,Object>> criteria(Caller c) { catalogAccess(c); return activeCriteria().stream().map(this::criterionView).toList(); }
    public Map<String,Object> getCriterion(String code,Caller c) { catalogAccess(c); var matches=activeCriteria().stream().filter(k->code.equals(k.getCode())).toList(); require(!matches.isEmpty(),404,"NOT_FOUND","Criterion was not found."); return criterionView(matches.getFirst()); }
    public List<Map<String,Object>> researchAreas() { return em.createQuery("select a from ResearchAreaEntity a where a.active=true order by a.code",ResearchAreaEntity.class).getResultList().stream().map(a->fields("id",a.getId(),"code",a.getCode(),"name",a.getName(),"description",a.getDescription())).toList(); }
    public Map<String,Object> approved(UUID id) {
        var p=find(ProposalEntity.class,id); require(p.getStatus()==ProposalStatus.APPROVED,409,"PROPOSAL_NOT_APPROVED","Proposal is not approved."); return read(find(ApiRecord.class,"approval:"+id).getPayload());
    }
    public void projectReference(UUID id,ProjectReference input) {
        var snapshot=approved(id); require(snapshot.get("approvalId").toString().equals(input.approvalId().toString()),409,"APPROVAL_MISMATCH","Approval does not match the approved snapshot.");
        em.lock(find(ProposalEntity.class,id),LockModeType.PESSIMISTIC_WRITE);
        var existing=em.find(ApiRecord.class,"project:"+id);
        if(existing!=null) { require(existing.getPayload().equals(input.projectId().toString()),409,"PROJECT_REFERENCE_CONFLICT","Proposal already references a different project."); return; }
        em.persist(new ApiRecord("project:"+id,input.approvalId().toString(),input.projectId().toString()));
    }
    public CollectionResponse<Map<String,Object>> proposals(Caller c,String q,List<ProposalStatus> statuses,String area,UUID researcher,UUID evaluator,Instant from,Instant to,int limit,String cursor,String sort) {
        require(from==null || to==null || !from.isAfter(to),400,"INVALID_DATE_RANGE","submittedFrom cannot be after submittedTo.");
        require(limit>=1 && limit<=100,400,"INVALID_LIMIT","limit must be between 1 and 100.");
        boolean descending=sort.startsWith("-"); String field=descending?sort.substring(1):sort;
        require(Set.of("id","title","createdAt","updatedAt","submittedAt").contains(field),400,"INVALID_SORT","Unsupported sort field.");
        var params=new LinkedHashMap<String,Object>(); params.put("coordinator",c.coordinator()); params.put("caller",c.id());
        String inactive="a.status not in (com.example.proposalservice.infraestructure.persistence.EvaluatorAssignmentStatus.REVOKED, com.example.proposalservice.infraestructure.persistence.EvaluatorAssignmentStatus.DECLINED)";
        var where=new StringBuilder("""
            select p from ProposalEntity p where
            (:coordinator=true or p.ownerUserId=:caller or exists (select r from ProposalResearcherEntity r where r.proposal=p and r.id.userId=:caller)
            or exists (select a from EvaluatorAssignmentEntity a where a.proposal=p and a.evaluatorUserId=:caller and
            """).append(inactive).append("))");
        if(q!=null) { where.append(" and (lower(p.title) like :q or lower(p.abstractText) like :q)"); params.put("q","%"+q.toLowerCase(Locale.ROOT)+"%"); }
        if(statuses!=null && !statuses.isEmpty()) { where.append(" and p.status in :statuses"); params.put("statuses",statuses); }
        if(area!=null) { where.append(" and p.researchArea.code=:area"); params.put("area",area); }
        if(researcher!=null) { where.append(" and (p.ownerUserId=:researcher or exists (select r from ProposalResearcherEntity r where r.proposal=p and r.id.userId=:researcher))"); params.put("researcher",researcher); }
        if(evaluator!=null) { where.append(" and exists (select a from EvaluatorAssignmentEntity a where a.proposal=p and a.evaluatorUserId=:evaluator and ").append(inactive).append(")"); params.put("evaluator",evaluator); }
        if(from!=null) { where.append(" and p.submittedAt>=:from"); params.put("from",from); }
        if(to!=null) { where.append(" and p.submittedAt<=:to"); params.put("to",to); }
        if(cursor!=null) {
            require(cursor.length()<=4096,400,"INVALID_CURSOR","Cursor is too long.");
            String[] tuple;
            try { tuple=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\u0000",-1); }
            catch(IllegalArgumentException e) { throw new ApiException(400,"INVALID_CURSOR","Malformed cursor."); }
            require(tuple.length==3 && tuple[0].equals(sort),400,"INVALID_CURSOR","Cursor does not match ordering.");
            params.put("lastId",UUID.fromString(tuple[2]));
            String expression="p."+field;
            if(tuple[1].isEmpty()) {
                where.append(" and (").append(expression).append(" is null and p.id>:lastId");
                if(!descending) where.append(" or ").append(expression).append(" is not null");
                where.append(")");
            } else {
                Object lastValue=field.equals("id")?UUID.fromString(tuple[1]):field.equals("title")?tuple[1]:Instant.parse(tuple[1]);
                params.put("lastValue",lastValue);
                where.append(" and (").append(expression).append(descending?"<:lastValue":">:lastValue")
                    .append(" or (").append(expression).append("=:lastValue and p.id>:lastId)");
                if(descending) where.append(" or ").append(expression).append(" is null");
                where.append(")");
            }
        }
        where.append(" order by p.").append(field).append(descending?" desc nulls last":" asc nulls first");
        if(!field.equals("id")) where.append(", p.id asc");
        var query=em.createQuery(where.toString(),ProposalEntity.class); params.forEach(query::setParameter);
        var found=query.setMaxResults(limit+1).getResultList();
        var views=found.stream().map(this::proposalView).toList();
        return CursorPages.page(views,limit,null,sort,Set.of(field));
    }
}
