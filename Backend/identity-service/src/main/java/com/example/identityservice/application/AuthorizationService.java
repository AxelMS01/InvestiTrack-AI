package com.example.identityservice.application;

import com.example.identityservice.api.*;
import com.example.identityservice.api.ApiModels.*;
import com.example.identityservice.domain.*;
import com.example.identityservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.example.identityservice.infrastructure.OwnerClient.unavailable;

@Service
public class AuthorizationService {
    private final IdentityRepository repo;
    private final OwnerClient owners;
    private static final Map<String,String> CAPABILITIES=Map.ofEntries(
        Map.entry("PROPOSAL_READ","proposal:read"),Map.entry("PROPOSAL_WRITE","proposal:write"),
        Map.entry("PROJECT_READ","project:read"),Map.entry("PROJECT_WRITE","project:write"),Map.entry("ACTIVITY_WRITE","activity:write"),
        Map.entry("PROJECT_CLOSE","project:close"),Map.entry("DOCUMENT_READ","document:read"),Map.entry("DOCUMENT_UPLOAD","document:upload"),
        Map.entry("DOCUMENT_DELETE","document:delete"),Map.entry("AI_SOURCE_READ","ai:query"));
    public AuthorizationService(IdentityRepository repo, OwnerClient owners) { this.repo=repo; this.owners=owners; }
    public Decision check(Check in) { return evaluate(in,System.nanoTime()+Duration.ofMillis(2500).toNanos()); }
    public FilterResult filter(Filter in) {
        if(in.resources().stream().map(Resource::id).distinct().count()!=in.resources().size())
            throw new ApiException(400,"DUPLICATE_RESOURCE","Resource IDs must be unique.");
        long deadline=System.nanoTime()+Duration.ofMillis(2500).toNanos(); var allowed=new ArrayList<UUID>();
        var initial=repo.find(in.subjectId()); String policyVersion=repo.policyVersion();
        for(var resource:in.resources()) if(evaluate(new Check(in.subjectId(),in.action(),resource),deadline).allowed()) allowed.add(resource.id());
        var current=repo.find(in.subjectId());
        if(!initial.map(User::version).equals(current.map(User::version)) || !policyVersion.equals(repo.policyVersion()))
            throw new ApiException(503,"IDENTITY_CHANGED_DURING_CHECK","Identity or policy changed during the batch. Retry with current state.");
        return new FilterResult(allowed,policyVersion,Instant.now());
    }
    private Decision evaluate(Check in, long deadline) {
        var user=repo.find(in.subjectId());
        if(user.isEmpty()) return decision(false,"SUBJECT_NOT_FOUND",0,null);
        var u=user.get();
        if(!u.active()) return decision(false,"ACCOUNT_INACTIVE",u.version(),null);
        if(!AccessPolicy.supported(in.action(),in.resource().type())) return decision(false,"UNKNOWN_ACTION_OR_RESOURCE",u.version(),null);
        if(!repo.capabilities(u.id()).contains(CAPABILITIES.get(in.action()))) return decision(false,"GLOBAL_PERMISSION_REQUIRED",u.version(),null);
        try {
            var result=resource(in.action(),in.resource(),u,new HashSet<>(repo.roles(u.id())),deadline);
            var current=repo.user(u.id());
            if(!current.active() || current.version()!=u.version()) throw new ApiException(503,"IDENTITY_CHANGED_DURING_CHECK","Identity changed during the access check. Retry with current state.");
            return result;
        } catch(ApiException e) {
            if(e.status==404) return decision(false,"RESOURCE_NOT_FOUND",u.version(),null);
            throw e;
        }
    }
    private Decision resource(String action, Resource r, User u, Set<String> roles, long deadline) {
        if(r.type().equals("DOCUMENT")) {
            var doc=owners.context("document","/internal/v1/documents/"+r.id()+"/access-context",deadline);
            match(doc,"documentId",r.id()); long version=number(doc,"accessVersion");
            String status=text(doc,"status"), classification=text(doc,"classification");
            if(!Set.of("AVAILABLE").contains(status)) return decision(false,"DOCUMENT_UNAVAILABLE",u.version(),version);
            var ctx=doc.path("context");
            UUID project=id(ctx,"projectId"), proposal=id(ctx,"proposalId");
            if((project==null)==(proposal==null)) throw unavailable();
            if(r.projectId()!=null && !r.projectId().equals(project)) return decision(false,"RESOURCE_CONTEXT_MISMATCH",u.version(),version);
            if(!Set.of("PUBLIC","INTERNAL","CONFIDENTIAL","RESTRICTED").contains(classification)) return decision(false,"CLASSIFICATION_DENIED",u.version(),version);
            if(classification.equals("RESTRICTED")) return decision(false,"CLASSIFICATION_DENIED",u.version(),version);
            // The proposal/project owner remains authoritative over membership; document classifications cannot broaden it.
            var parent=project!=null?project(action,new Resource("PROJECT",project,project),u,roles,deadline,classification)
                :proposal(action,new Resource("PROPOSAL",proposal,null),u,classification.equals("CONFIDENTIAL")?Set.of():roles,deadline);
            return decision(parent.allowed(),parent.reasonCode(),u.version(),version);
        }
        if(r.type().equals("PROPOSAL")) return proposal(action,r,u,roles,deadline);
        Resource project=r;
        if(!r.type().equals("PROJECT")) {
            var node=owners.context("project","/internal/v1/"+(r.type().equals("ACTIVITY")?"activities":"milestones")+"/"+r.id()+"/access-context?subjectId="+u.id(),deadline);
            match(node,r.type().equals("ACTIVITY")?"activityId":"milestoneId",r.id());
            var ownerProject=id(node,"projectId"); if(ownerProject==null) throw unavailable();
            if(r.projectId()!=null&&!r.projectId().equals(ownerProject)) return decision(false,"RESOURCE_CONTEXT_MISMATCH",u.version(),number(node,"accessVersion"));
            project=new Resource("PROJECT",ownerProject,ownerProject);
        }
        return project(action,project,u,roles,deadline,null);
    }
    private Decision proposal(String action, Resource r, User u, Set<String> roles, long deadline) {
        if(r.projectId()!=null) return decision(false,"RESOURCE_CONTEXT_MISMATCH",u.version(),null);
        var node=owners.context("proposal","/internal/v1/proposals/"+r.id()+"/access-context?subjectId="+u.id(),deadline);
        match(node,"proposalId",r.id()); var subject=node.path("subject"); match(subject,"userId",u.id());
        String status=text(node,"status");
        if(!Set.of("DRAFT","SUBMITTED","UNDER_REVIEW","CHANGES_REQUESTED","APPROVED","REJECTED","WITHDRAWN").contains(status)) throw unavailable();
        boolean allowed=AccessPolicy.proposal(action,status,roles,bool(subject,"isOwner"),bool(subject,"isCollaborator"),bool(subject,"isAssignedEvaluator"));
        return decision(allowed,allowed?"PROPOSAL_ACCESS":"PROPOSAL_ACCESS_DENIED",u.version(),number(node,"accessVersion"));
    }
    private Decision project(String action, Resource r, User u, Set<String> roles, long deadline, String documentClassification) {
        if(r.projectId()!=null&&!r.projectId().equals(r.id())) return decision(false,"RESOURCE_CONTEXT_MISMATCH",u.version(),null);
        var node=owners.context("project","/internal/v1/projects/"+r.id()+"/access-context?subjectId="+u.id(),deadline);
        match(node,"projectId",r.id()); var subject=node.path("subject"); match(subject,"userId",u.id());
        String membership=text(subject,"membershipStatus"), classification=text(node,"classification"), status=text(node,"status");
        if(!Set.of("APPROVED","ACTIVE","DELAYED","COMPLETED","CLOSED","CANCELLED").contains(status)) throw unavailable();
        if(!Set.of("PUBLIC","INTERNAL","CONFIDENTIAL","RESTRICTED").contains(classification)) throw unavailable();
        var permissions=subject.path("permissions"); if(!permissions.isArray()) throw unavailable();
        var grants=new HashSet<String>(); for(var p:permissions) { if(!p.isString()) throw unavailable(); grants.add(p.asString()); }
        boolean member=membership.equals("ACTIVE");
        if(documentClassification!=null && Set.of("CONFIDENTIAL","RESTRICTED").contains(documentClassification) && !member)
            return decision(false,"CLASSIFICATION_DENIED",u.version(),number(node,"accessVersion"));
        boolean allowed=AccessPolicy.project(action,status,classification,roles,member,grants);
        return decision(allowed,allowed?(member?"PROJECT_MEMBER":"GLOBAL_ROLE"):"PROJECT_ACCESS_DENIED",u.version(),number(node,"accessVersion"));
    }
    private Decision decision(boolean allowed, String reason, long identityVersion, Long accessVersion) {
        return new Decision(allowed,repo.policyVersion(),reason,identityVersion,accessVersion);
    }
    private static String text(JsonNode n,String key) { var p=n.path(key); if(!p.isString()||p.asString().isBlank()) throw unavailable(); return p.asString(); }
    private static long number(JsonNode n,String key) { var p=n.path(key); if(!p.isIntegralNumber()||p.asLong()<0) throw unavailable(); return p.asLong(); }
    private static boolean bool(JsonNode n,String key) { var p=n.path(key); if(!p.isBoolean()) throw unavailable(); return p.asBoolean(); }
    private static UUID id(JsonNode n,String key) { if(!n.has(key)) return null; try { return UUID.fromString(text(n,key)); } catch(IllegalArgumentException e) { throw unavailable(); } }
    private static void match(JsonNode n,String key,UUID expected) { if(!expected.equals(id(n,key))) throw unavailable(); }
}
