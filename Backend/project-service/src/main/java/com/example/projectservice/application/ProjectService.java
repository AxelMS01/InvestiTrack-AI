package com.example.projectservice.application;

import com.example.projectservice.api.*;
import com.example.projectservice.api.ApiModels.*;
import com.example.projectservice.domain.ProjectRules;
import com.example.projectservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.example.projectservice.api.ApiException.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

@Service
@Transactional
public class ProjectService {
    private final ProjectStore store;
    private final RemoteServices remote;
    private final DomainEvents events;
    private final DocumentHolds holds;
    private static final Set<String> PERMISSIONS=Set.of("PROJECT_READ","PROJECT_WRITE","ACTIVITY_WRITE","MILESTONE_WRITE","DOCUMENT_READ","DOCUMENT_UPLOAD","DOCUMENT_DELETE","AI_SOURCE_READ","PROJECT_CLOSE","REPORT_WRITE","PUBLICATION_WRITE");
    public ProjectService(ProjectStore store,RemoteServices remote,DomainEvents events,DocumentHolds holds) {
        this.store=store;this.remote=remote;this.events=events;this.holds=holds;
    }
    public record Result(int status,Map<String,Object> body) { }
    @SuppressWarnings("unchecked")
    public Result idempotent(Caller caller,String operation,String key,Object input,Supplier<Result> work) {
        if(key==null)return work.get();
        require(!key.isBlank()&&key.length()<=200,400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key must contain 1 to 200 characters.");
        store.lock("idempotency:"+caller.id()+":"+operation+":"+key);
        var params=map("caller",caller.id().toString(),"operation",operation,"key",key);
        String hash=CursorPages.hash(store.json().writeValueAsString(input));
        var saved=store.one("select * from idempotency_record where caller_id=:caller and operation=:operation and idempotency_key=:key and expires_at>now()",params);
        if(saved.isPresent()) {
            require(hash.equals(saved.get().get("requestHash")),409,"IDEMPOTENCY_CONFLICT","This key was used with a different request.");
            var response=(Map<String,Object>)saved.get().get("responseBody");
            var body=(Map<String,Object>)response.get("body");
            UUID projectId=UUID.fromString(Objects.toString(body.getOrDefault("projectId",body.get("id"))));
            var project=project(caller,projectId,false);
            if(operation.equals("POST /projects"))require(caller.role("COORDINATOR"),403,"FORBIDDEN","Coordinator role is required.");
            else if(operation.endsWith("/members"))manage(project,caller);
            else {String permission=operation.endsWith("/evidence")?"DOCUMENT_UPLOAD":operation.endsWith("/activities")?"ACTIVITY_WRITE":operation.endsWith("/milestones")?"MILESTONE_WRITE":"PUBLICATION_WRITE";
                require(canWrite(project,caller,permission),403,"FORBIDDEN","Current project permission is required to replay this result.");}
            return new Result(((Number)response.get("status")).intValue(),body);
        }
        store.execute("delete from idempotency_record where caller_id=:caller and operation=:operation and idempotency_key=:key",params);
        var result=work.get();store.insert("idempotency_record",map("callerId",caller.id().toString(),"operation",operation,"idempotencyKey",key,
            "requestHash",hash,"responseBody",map("status",result.status(),"body",result.body()),"createdAt",Instant.now(),"expiresAt",Instant.now().plus(Duration.ofHours(24))));
        return result;
    }
    public Result create(Caller caller,CreateProject input) {
        require(caller.role("COORDINATOR"),403,"FORBIDDEN","Coordinator role is required to create projects.");
        return createApproved(remote.approved(input.proposalId(),input.approvalId()),input,caller);
    }
    /** Shared by the HTTP command and the durable ProposalApproved consumer. */
    public Result createApproved(Map<String,Object> snapshot,CreateProject input,Caller caller) {
        UUID proposal=input.proposalId();store.lock("proposal-project:"+proposal);
        UUID approval,principal,coordinator;long proposalVersion;LocalDate start,end;String title,area;List<?> collaborators;
        try {
            require(proposal.toString().equals(snapshot.get("proposalId").toString()),503,"INVALID_APPROVAL_SNAPSHOT","The approved snapshot does not match the proposal.");
            approval=UUID.fromString(snapshot.get("approvalId").toString());
            if(input.approvalId()!=null)require(approval.equals(input.approvalId()),409,"APPROVAL_MISMATCH","The approval identifier does not match.");
            if(snapshot.get("status")!=null)require("APPROVED".equals(snapshot.get("status").toString()),409,"PROPOSAL_NOT_APPROVED","Only an approved proposal can create a project.");
            Instant.parse(snapshot.get("approvedAt").toString());
            require(snapshot.get("proposalVersion") instanceof Integer||snapshot.get("proposalVersion") instanceof Long,503,"INVALID_APPROVAL_SNAPSHOT","Proposal version must be an integer.");
            proposalVersion=((Number)snapshot.get("proposalVersion")).longValue();require(proposalVersion>0,503,"INVALID_APPROVAL_SNAPSHOT","The snapshot version is invalid.");
            principal=UUID.fromString(snapshot.get("principalResearcherId").toString());
            title=snapshot.get("title").toString();area=snapshot.get("researchAreaCode").toString();
            require(!title.isBlank()&&title.length()<=500&&!area.isBlank()&&area.length()<=50,503,"INVALID_APPROVAL_SNAPSHOT","Snapshot title or research area is invalid.");
            var plan=(Map<?,?>)snapshot.get("creationPlan");coordinator=UUID.fromString(plan.get("coordinatorId").toString());
            start=plan.get("startDate")==null?null:LocalDate.parse(plan.get("startDate").toString());end=LocalDate.parse(plan.get("plannedEndDate").toString());
            ProjectRules.dates(start,end);collaborators=(List<?>)snapshot.get("collaborators");require(collaborators.size()<=100,503,"INVALID_APPROVAL_SNAPSHOT","Too many approved collaborators.");
        }catch(ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
        require((input.coordinatorId()==null||input.coordinatorId().equals(coordinator))&&(input.startDate()==null||input.startDate().equals(start))
            &&(input.plannedEndDate()==null||input.plannedEndDate().equals(end)),409,"CREATION_PLAN_CONFLICT","Creation inputs must match the approved plan. Change project details after creation.");
        var members=new LinkedHashMap<UUID,String>();members.put(principal,"PRINCIPAL_RESEARCHER");
        for(Object raw:collaborators) {
            try {var collaborator=(Map<?,?>)raw;var id=UUID.fromString(collaborator.get("userId").toString());String role=collaborator.get("projectRole").toString();
                require(Set.of("RESEARCHER","STUDENT","COLLABORATOR").contains(role),503,"INVALID_APPROVAL_SNAPSHOT","Invalid collaborator role.");
                require(!members.containsKey(id),503,"INVALID_APPROVAL_SNAPSHOT","Duplicate collaborator in approval snapshot.");members.put(id,role);
            }catch(ApiException e){throw e;}catch(RuntimeException e){throw unavailable();}
        }
        var normalizedCollaborators=members.entrySet().stream().filter(entry->!entry.getKey().equals(principal))
            .sorted(Map.Entry.comparingByKey()).map(entry->map("userId",entry.getKey().toString(),"projectRole",entry.getValue())).toList();
        Object summary=snapshot.get("abstract");require(summary==null||(summary instanceof String&&summary.toString().length()<=50000),503,"INVALID_APPROVAL_SNAPSHOT","The approved abstract is invalid.");
        var normalized=map("proposalId",proposal.toString(),"approvalId",approval.toString(),"proposalVersion",proposalVersion,
            "approvedAt",Instant.parse(snapshot.get("approvedAt").toString()).toString(),"title",title,"abstract",summary,"researchAreaCode",area,
            "principalResearcherId",principal.toString(),"collaborators",normalizedCollaborators,
            "creationPlan",map("coordinatorId",coordinator.toString(),"startDate",start,"plannedEndDate",end));
        var existing=store.one("select * from project where source_proposal_id=:proposal",map("proposal",proposal));
        if(existing.isPresent()) {
            var project=existing.get();require(approval.equals(project.get("approvalId"))&&((Number)project.get("proposalVersion")).longValue()==proposalVersion
                &&store.json().readTree(store.json().writeValueAsString(project.get("approvedSnapshot"))).equals(store.json().readTree(store.json().writeValueAsString(normalized))),409,"PROJECT_APPROVAL_CONFLICT","This proposal already has a project with a different approval snapshot.");
            if(caller!=null)access(project,caller);return new Result(200,view(project));
        }
        members.putIfAbsent(coordinator,"COLLABORATOR");for(var user:members.keySet())remote.activeUser(user);
        UUID actor=caller==null?principal:caller.id();UUID id=UUID.randomUUID();var now=Instant.now();
        var project=map("id",id,"projectNumber","PRJ-"+id.toString().toUpperCase(Locale.ROOT),"sourceProposalId",proposal,"approvalId",approval,
            "proposalVersion",proposalVersion,"approvedSnapshot",normalized,"title",title,"abstractText",snapshot.get("abstract"),"researchAreaCode",area,
            "status","APPROVED","classification","INTERNAL","accessVersion",1L,"principalResearcherId",principal,"coordinatorUserId",coordinator,
            "startDate",start,"plannedEndDate",end,"progressPercent",BigDecimal.ZERO.setScale(2),"progressMethod","ACTIVITY_WEIGHT",
            "createdAt",now,"createdBy",actor,"updatedAt",now,"updatedBy",actor,"version",1L);
        store.insert("project",project);
        members.forEach((user,role)->store.insert("project_member",map("projectId",id,"userId",user,"projectRole",role,"status","ACTIVE","joinedAt",now,"addedBy",actor,"version",1L)));
        history(project,null,"Approved proposal converted to project",actor);
        store.insert("proposal_reference_job",map("proposalId",proposal,"approvalId",approval,"projectId",id));
        events.write("ProjectCreated",project,caller,map("projectId",id,"proposalId",proposal,"approvalId",approval,"status","APPROVED",
            "principalResearcherId",principal,"coordinatorId",coordinator,"plannedEndDate",end));
        return new Result(201,view(project));
    }
    public Map<String,Object> get(Caller caller,UUID id) {return view(project(caller,id,false));}
    public Page<Map<String,Object>> projects(Caller caller,Map<String,String> filters,List<String> statuses,int limit,String cursor) {
        for(var s:statuses)ProjectStatus.valueOf(s);
        String binding=caller.id()+":"+new TreeSet<>(caller.roles())+":"+new TreeMap<>(filters)+":"+new TreeSet<>(statuses);
        range(filters);UUID after=CursorPages.after(cursor,binding,limit);
        var args=map("actor",caller.id(),"admin",caller.role("ADMIN"),"coordinator",caller.role("COORDINATOR"),"after",after,"limit",limit+1);
        var sql=new StringBuilder("select p.* from project p where (:admin or (:coordinator and p.coordinator_user_id=:actor) or exists(select 1 from project_member m where m.project_id=p.id and m.user_id=:actor and m.status='ACTIVE'))");
        if(!statuses.isEmpty()){sql.append(" and p.status in (:statuses)");args.put("statuses",statuses);}
        if(filters.containsKey("q")){sql.append(" and lower(p.title) like :q escape '\\'");args.put("q","%"+escape(filters.get("q").toLowerCase(Locale.ROOT))+"%");}
        if(filters.containsKey("researchArea")){sql.append(" and p.research_area_code=:area");args.put("area",filters.get("researchArea"));}
        if(filters.containsKey("memberId")){sql.append(" and exists(select 1 from project_member f where f.project_id=p.id and f.user_id=:member and f.status='ACTIVE')");args.put("member",UUID.fromString(filters.get("memberId")));}
        if(filters.containsKey("coordinatorId")){sql.append(" and p.coordinator_user_id=:coordinatorId");args.put("coordinatorId",UUID.fromString(filters.get("coordinatorId")));}
        if(filters.containsKey("delayed")){String v=filters.get("delayed");require(Set.of("true","false").contains(v),400,"INVALID_FILTER","Delayed must be true or false.");sql.append(v.equals("true")?" and p.status='DELAYED'":" and p.status<>'DELAYED'");}
        dateFilter(sql,args,filters,"deadlineFrom","p.planned_end_date",">=");dateFilter(sql,args,filters,"deadlineTo","p.planned_end_date","<=");
        Map<String,Object> anchor=cursor==null?null:project(caller,after,false);
        sorting(sql,args,filters.getOrDefault("sort","-updatedAt"),Map.of("id","id","title","title","updatedAt","updated_at","createdAt","created_at","plannedEndDate","planned_end_date","progressPercent","progress_percent"),"p.",anchor);
        sql.append(" limit :limit");var rows=store.query(sql.toString(),args);rows.replaceAll(this::view);return CursorPages.page(rows,binding,limit);
    }
    public Map<String,Object> patch(Caller caller,UUID id,PatchProject input,String match) {
        var project=project(caller,id,true);writer(project,caller,"PROJECT_WRITE");ProjectRules.etag(match,version(project));
        var changes=map();put(changes,"title",input.title());put(changes,"abstractText",input.abstractText());put(changes,"startDate",input.startDate());put(changes,"plannedEndDate",input.plannedEndDate());
        if(input.title()!=null)ProjectRules.text(input.title());
        if(input.coordinatorId()!=null){manage(project,caller);remote.activeUser(input.coordinatorId());changes.put("coordinatorUserId",input.coordinatorId());}
        if(input.classification()!=null){manage(project,caller);changes.put("classification",input.classification().name());}
        if(input.progressMethod()!=null){manage(project,caller);changes.put("progressMethod",input.progressMethod().name());}
        ProjectRules.dates((LocalDate)changes.getOrDefault("startDate",project.get("startDate")),(LocalDate)changes.getOrDefault("plannedEndDate",project.get("plannedEndDate")));
        if(input.classification()!=null||input.coordinatorId()!=null)changes.put("accessVersion",number(project,"accessVersion")+1);
        save("project",project,changes,caller.id());recalculate(project,caller);events.write("ProjectChanged",project,caller,map("projectId",id));return view(project);
    }
    public Map<String,Object> transition(Caller caller,UUID id,ProjectTransition input,String match) {
        var project=project(caller,id,true);require(caller.role("COORDINATOR")&&Objects.equals(project.get("coordinatorUserId"),caller.id()),403,"FORBIDDEN","The assigned coordinator must perform project transitions.");
        ProjectRules.etag(match,version(project));String from=text(project,"status"),to=input.targetStatus().name();ProjectRules.transition("project",from,to);
        var changes=map("status",to);if(to.equals("ACTIVE"))require(project.get("startDate")!=null,422,"START_DATE_REQUIRED","An active project requires a start date.");
        if(to.equals("COMPLETED")||to.equals("CLOSED"))completionChecks(project,caller,to.equals("CLOSED"));
        if(to.equals("COMPLETED"))changes.put("actualCompletionDate",LocalDate.now(ZoneOffset.UTC));
        if(to.equals("ACTIVE")){changes.put("actualCompletionDate",null);}
        if(to.equals("CLOSED")) {
            var report=report(project);var validation=remote.validateDocument(id,(UUID)report.get("documentId"),((Number)report.get("documentVersion")).intValue(),"FINAL_REPORT",caller);
            require(Objects.equals(validation.get("sha256"),report.get("sha256")),409,"DOCUMENT_CHANGED","The final report checksum changed after submission.");
            UUID hold=holds.reserve(id,(UUID)report.get("documentId"),((Number)report.get("documentVersion")).intValue(),caller);
            store.update("final_report",(UUID)report.get("id"),map("holdId",hold));changes.put("closureDate",LocalDate.now(ZoneOffset.UTC));changes.put("closedBy",caller.id());
        }
        save("project",project,changes,caller.id());history(project,from,input.reason(),caller.id());
        events.write("ProjectChanged",project,caller,map("projectId",id));
        if(to.equals("CLOSED")){var report=report(project);events.write("ProjectClosed",project,caller,map("projectId",id,"closedAt",Instant.now(),"closedBy",caller.id(),"finalReportDocumentId",report.get("documentId"),"finalReportVersion",report.get("documentVersion")));}
        return view(project);
    }
    private void completionChecks(Map<String,Object> project,Caller caller,boolean closing) {
        UUID id=(UUID)project.get("id");var active=store.query("select id from activity where project_id=:id and status not in ('COMPLETED','CANCELLED') union all select id from milestone where project_id=:id and status not in ('COMPLETED','CANCELLED')",map("id",id));
        boolean completed=active.isEmpty()&&calculation(project).percent().compareTo(BigDecimal.valueOf(100))==0;closureCheck(id,"ALL_WORK_COMPLETED",completed,caller.id());
        require(completed,409,"WORK_INCOMPLETE","Complete or cancel all activities and milestones before completing or closing the project.");
        if(closing){var report=report(project);boolean submitted="SUBMITTED".equals(report.get("status"));closureCheck(id,"FINAL_REPORT_SUBMITTED",submitted,caller.id());require(submitted,409,"FINAL_REPORT_REQUIRED","A submitted final report is required to close the project.");}
    }
    private void closureCheck(UUID id,String code,boolean value,UUID actor) {
        store.execute("insert into project_closure_check(id,project_id,condition_code,satisfied,checked_at,checked_by) values(:row,:id,:code,:value,now(),:actor) on conflict(project_id,condition_code) do update set satisfied=:value,checked_at=now(),checked_by=:actor",map("row",UUID.randomUUID(),"id",id,"code",code,"value",value,"actor",actor));
    }
    public Page<Map<String,Object>> history(Caller caller,UUID id,int limit,String cursor) {
        project(caller,id,false);return collection("project_status_history",id,Map.of(),limit,cursor,caller.id()+":history:"+id);
    }
    public Map<String,Object> progress(Caller caller,UUID id) {var project=project(caller,id,false);return progressView(project);}
    public Map<String,Object> recalculate(Caller caller,UUID id,String match) {
        var project=project(caller,id,true);require(caller.manager()&&(caller.role("ADMIN")||Objects.equals(caller.id(),project.get("coordinatorUserId"))),403,"FORBIDDEN","An assigned coordinator or administrator is required.");
        ProjectRules.editable(text(project,"status"));ProjectRules.etag(match,version(project));save("project",project,map(),caller.id());recalculate(project,caller);return progressView(project);
    }
    public Page<Map<String,Object>> members(Caller caller,UUID id,int limit,String cursor) {
        project(caller,id,false);String binding=caller.id()+":members:"+id;var after=CursorPages.after(cursor,binding,limit);
        var rows=store.query("select *,user_id as id from project_member where project_id=:id and user_id>:after and status='ACTIVE' order by user_id limit :limit",map("id",id,"after",after,"limit",limit+1));
        rows.forEach(row->row.put("permissions",permissions(id,(UUID)row.get("userId"))));return CursorPages.page(rows,binding,limit);
    }
    public Map<String,Object> member(Caller caller,UUID projectId,UUID user) {project(caller,projectId,false);return memberView(projectId,user);}
    public Map<String,Object> addMember(Caller caller,UUID id,Member input,String match) {
        var project=project(caller,id,true);manage(project,caller);ProjectRules.etag(match,version(project));validateGrants(input.projectRole(),input.permissions());
        require(input.projectRole()!=MemberRole.PRINCIPAL_RESEARCHER,422,"PRINCIPAL_IMMUTABLE","The approved principal researcher cannot be replaced through membership edits.");remote.activeUser(input.userId());
        var old=store.one("select * from project_member where project_id=:id and user_id=:user",map("id",id,"user",input.userId()));
        require(old.isEmpty()||!"ACTIVE".equals(old.get().get("status")),409,"MEMBER_EXISTS","The user is already a project member.");
        if(old.isEmpty())store.insert("project_member",map("projectId",id,"userId",input.userId(),"projectRole",input.projectRole(),"status","ACTIVE","joinedAt",Instant.now(),"addedBy",caller.id(),"version",1));
        else store.execute("update project_member set project_role=:role,status='ACTIVE',joined_at=now(),added_by=:actor,removed_at=null,removed_by=null,version=version+1 where project_id=:id and user_id=:user",map("id",id,"user",input.userId(),"role",input.projectRole(),"actor",caller.id()));
        grants(id,input.userId(),input.permissions(),caller.id());membershipChanged(project,caller,input.userId(),"ADDED");return memberView(id,input.userId());
    }
    public Map<String,Object> patchMember(Caller caller,UUID id,UUID user,PatchMember input,String match) {
        var project=project(caller,id,true);manage(project,caller);var member=memberView(id,user);ProjectRules.etag(match,version(member));
        validateGrants(input.projectRole(),input.permissions());
        require(!Objects.equals(project.get("principalResearcherId"),user)&&input.projectRole()!=MemberRole.PRINCIPAL_RESEARCHER,409,"PRINCIPAL_IMMUTABLE","The approved principal membership is protected.");
        store.execute("update project_member set project_role=:role,version=version+1 where project_id=:id and user_id=:user",map("id",id,"user",user,"role",input.projectRole()));
        grants(id,user,input.permissions(),caller.id());membershipChanged(project,caller,user,"UPDATED");return memberView(id,user);
    }
    public void removeMember(Caller caller,UUID id,UUID user,String match) {
        var project=project(caller,id,true);manage(project,caller);var member=memberView(id,user);ProjectRules.etag(match,version(member));
        require(!Objects.equals(project.get("principalResearcherId"),user),409,"PRINCIPAL_IMMUTABLE","The principal researcher cannot be removed.");
        require(store.query("select id from activity where project_id=:id and responsible_user_id=:user and status not in ('COMPLETED','CANCELLED')",map("id",id,"user",user)).isEmpty(),409,"MEMBER_HAS_ASSIGNMENTS","Reassign open activities before removing the member.");
        store.execute("update project_member set status='REMOVED',removed_at=now(),removed_by=:actor,version=version+1 where project_id=:id and user_id=:user",map("id",id,"user",user,"actor",caller.id()));
        grants(id,user,List.of(),caller.id());membershipChanged(project,caller,user,"REMOVED");
    }
    private void membershipChanged(Map<String,Object> project,Caller caller,UUID user,String change) {
        save("project",project,map("accessVersion",number(project,"accessVersion")+1),caller.id());
        events.write("ProjectMemberChanged",project,caller,map("projectId",project.get("id"),"userId",user,"change",change,"accessVersion",project.get("accessVersion")));
    }
    private void validateGrants(MemberRole role,List<String> permissions) {
        var grants=permissions==null?List.<String>of():permissions;
        require(new HashSet<>(grants).size()==grants.size()&&PERMISSIONS.containsAll(grants),422,"INVALID_PERMISSIONS","Permissions must be distinct supported project grants.");
    }
    private void grants(UUID project,UUID user,List<String> permissions,UUID actor) {
        store.execute("delete from project_member_permission where project_id=:project and user_id=:user",map("project",project,"user",user));
        for(String permission:permissions==null?List.<String>of():permissions)store.insert("project_member_permission",map("projectId",project,"userId",user,"permissionCode",permission,"grantedAt",Instant.now(),"grantedBy",actor));
    }
    public Page<Map<String,Object>> items(Caller caller,UUID projectId,String table,Map<String,String> filters,List<String> statuses,int limit,String cursor) {
        project(caller,projectId,false);String binding=caller.id()+":"+projectId+":"+table+":"+new TreeMap<>(filters)+":"+new TreeSet<>(statuses);
        range(filters);var after=CursorPages.after(cursor,binding,limit);var parameters=map("project",projectId,"after",after,"limit",limit+1);
        var sql=new StringBuilder("select * from "+itemTable(table)+" where project_id=:project");
        if(!statuses.isEmpty()) {for(var status:statuses) {if(table.equals("activity"))ActivityStatus.valueOf(status);else MilestoneStatus.valueOf(status);}sql.append(" and status in (:statuses)");parameters.put("statuses",statuses);}
        for(String key:List.of("responsibleUserId","milestoneId")) if(filters.containsKey(key)) {
            require(table.equals("activity"),400,"INVALID_FILTER","This filter is only supported for activities.");sql.append(" and ").append(key.equals("milestoneId")?"milestone_id":"responsible_user_id").append("=:").append(key);parameters.put(key,UUID.fromString(filters.get(key)));
        }
        if(filters.containsKey("overdue")){String overdue=filters.get("overdue");require(Set.of("true","false").contains(overdue),400,"INVALID_FILTER","Overdue must be true or false.");sql.append(overdue.equals("true")?" and (deadline<:today and status not in ('COMPLETED','CANCELLED'))":" and not (deadline<:today and status not in ('COMPLETED','CANCELLED'))");parameters.put("today",LocalDate.now(ZoneOffset.UTC));}
        dateFilter(sql,parameters,filters,"deadlineFrom","deadline",">=");dateFilter(sql,parameters,filters,"deadlineTo","deadline","<=");
        var columns=new HashMap<>(Map.of("id","id","name","name","deadline","deadline","updatedAt","updated_at","createdAt","created_at","weight","weight"));
        if(table.equals("milestone"))columns.put("displayOrder","display_order");
        sorting(sql,parameters,filters.getOrDefault("sort","id"),columns,"",cursor==null?null:child(table,after,projectId,false));
        sql.append(" limit :limit");return CursorPages.page(store.query(sql.toString(),parameters),binding,limit);
    }
    public Map<String,Object> item(Caller caller,UUID project,UUID id,String table) {project(caller,project,false);return child(table,id,project,false);}
    public Map<String,Object> addActivity(Caller caller,UUID id,ActivityInput input,String match) {
        var project=project(caller,id,true);writer(project,caller,"ACTIVITY_WRITE");workEditable(project);ProjectRules.etag(match,version(project));
        memberView(id,input.responsibleUserId());if(input.milestoneId()!=null)child("milestone",input.milestoneId(),id,false);ProjectRules.dates(input.startDate(),input.deadline());
        var row=created(caller.id());row.putAll(map("projectId",id,"name",input.name().trim(),"description",input.description(),"responsibleUserId",input.responsibleUserId(),"startDate",input.startDate(),"deadline",input.deadline(),"milestoneId",input.milestoneId(),"weight",weight(input.weight()),"status","NOT_STARTED"));
        store.insert("activity",row);itemChanged(project,caller,"activity",(UUID)row.get("id"),"CREATED");return row;
    }
    public Map<String,Object> patchActivity(Caller caller,UUID projectId,UUID id,ActivityPatch input,String match) {
        var project=project(caller,projectId,true);var row=child("activity",id,projectId,true);workEditable(project);assigneeOrWriter(project,row,caller);ProjectRules.etag(match,version(row));
        require(!Set.of("COMPLETED","CANCELLED").contains(text(row,"status")),409,"ITEM_LOCKED","Completed and cancelled activities are immutable.");
        if(!canWrite(project,caller,"ACTIVITY_WRITE"))require(input.responsibleUserId()==null&&input.weight()==null&&input.milestoneId()==null&&!Boolean.TRUE.equals(input.clearMilestone()),403,"FORBIDDEN","An assignee cannot change assignment, weight or milestone.");
        var changes=map();put(changes,"name",input.name());put(changes,"description",input.description());put(changes,"responsibleUserId",input.responsibleUserId());put(changes,"startDate",input.startDate());put(changes,"deadline",input.deadline());put(changes,"weight",input.weight());
        if(input.name()!=null)ProjectRules.text(input.name());if(input.responsibleUserId()!=null)memberView(projectId,input.responsibleUserId());
        require(!(input.milestoneId()!=null&&Boolean.TRUE.equals(input.clearMilestone())),422,"INVALID_MILESTONE","Set a milestone or clear it, not both.");
        if(input.milestoneId()!=null){child("milestone",input.milestoneId(),projectId,false);changes.put("milestoneId",input.milestoneId());}
        if(Boolean.TRUE.equals(input.clearMilestone()))changes.put("milestoneId",null);
        ProjectRules.dates((LocalDate)changes.getOrDefault("startDate",row.get("startDate")),(LocalDate)changes.getOrDefault("deadline",row.get("deadline")));
        save("activity",row,changes,caller.id());itemChanged(project,caller,"activity",id,"UPDATED");return row;
    }
    public Map<String,Object> addMilestone(Caller caller,UUID id,MilestoneInput input,String match) {
        var project=project(caller,id,true);writer(project,caller,"MILESTONE_WRITE");workEditable(project);ProjectRules.etag(match,version(project));
        var row=created(caller.id());row.putAll(map("projectId",id,"name",input.name().trim(),"description",input.description(),"deadline",input.deadline(),"weight",weight(input.weight()),"displayOrder",input.displayOrder()==null?0:input.displayOrder(),"status","PLANNED"));
        store.insert("milestone",row);itemChanged(project,caller,"milestone",(UUID)row.get("id"),"CREATED");return row;
    }
    public Map<String,Object> patchMilestone(Caller caller,UUID projectId,UUID id,MilestonePatch input,String match) {
        var project=project(caller,projectId,true);writer(project,caller,"MILESTONE_WRITE");workEditable(project);var row=child("milestone",id,projectId,true);ProjectRules.etag(match,version(row));
        require(!Set.of("COMPLETED","CANCELLED").contains(text(row,"status")),409,"ITEM_LOCKED","Completed and cancelled milestones are immutable.");
        var changes=map();put(changes,"name",input.name());put(changes,"description",input.description());put(changes,"deadline",input.deadline());put(changes,"weight",input.weight());put(changes,"displayOrder",input.displayOrder());
        if(input.name()!=null)ProjectRules.text(input.name());save("milestone",row,changes,caller.id());itemChanged(project,caller,"milestone",id,"UPDATED");return row;
    }
    public Map<String,Object> itemTransition(Caller caller,UUID projectId,UUID id,String table,String target,String reason,String match) {
        var project=project(caller,projectId,true);workEditable(project);var row=child(table,id,projectId,true);
        if(table.equals("activity"))assigneeOrWriter(project,row,caller);else writer(project,caller,"MILESTONE_WRITE");ProjectRules.etag(match,version(row));ProjectRules.transition(table,text(row,"status"),target);
        if(table.equals("milestone")&&Set.of("COMPLETED","CANCELLED").contains(target))require(store.query("select id from activity where project_id=:project and milestone_id=:id and status not in ('COMPLETED','CANCELLED')",map("project",projectId,"id",id)).isEmpty(),409,"MILESTONE_HAS_OPEN_ACTIVITIES","Complete, cancel or detach activities first.");
        var changes=map("status",target,"completedAt",target.equals("COMPLETED")?Instant.now():null);
        if(table.equals("activity")) {if(target.equals("BLOCKED"))require(reason!=null&&!reason.isBlank(),422,"BLOCK_REASON_REQUIRED","A blocked activity requires a reason.");changes.put("blockReason",target.equals("BLOCKED")?reason:null);}
        save(table,row,changes,caller.id());itemChanged(project,caller,table,id,"UPDATED");return row;
    }
    public void deleteItem(Caller caller,UUID projectId,UUID id,String table,String match) {
        var project=project(caller,projectId,true);writer(project,caller,table.equals("activity")?"ACTIVITY_WRITE":"MILESTONE_WRITE");workEditable(project);var row=child(table,id,projectId,true);ProjectRules.etag(match,version(row));
        require(Set.of("NOT_STARTED","PLANNED","CANCELLED").contains(text(row,"status")),409,"ITEM_PROTECTED","Only unstarted or cancelled work can be deleted.");
        // FK constraints retain evidence and associated activities; business history is never cascaded away.
        store.execute("delete from "+itemTable(table)+" where id=:id",map("id",id));
        store.insert("aggregate_tombstone",map("aggregateType",table.toUpperCase(Locale.ROOT),"aggregateId",id,"projectId",projectId,"version",version(row)+1,"deletedAt",Instant.now()));
        itemChanged(project,caller,table,id,"DELETED");
    }
    public Page<Map<String,Object>> evidence(Caller caller,UUID projectId,UUID id,String table,int limit,String cursor) {
        item(caller,projectId,id,table);return collection("evidence_link",projectId,Map.of(table+"_id",id),limit,cursor,caller.id()+":evidence:"+table+":"+id);
    }
    public Map<String,Object> addEvidence(Caller caller,UUID projectId,UUID id,String table,Evidence input,String match) {
        var project=project(caller,projectId,true);writer(project,caller,"DOCUMENT_UPLOAD");workEditable(project);var item=child(table,id,projectId,true);ProjectRules.etag(match,version(item));
        var validation=remote.validateDocument(projectId,input.documentId(),input.documentVersion(),"EVIDENCE",caller);
        var row=map("id",UUID.randomUUID(),"projectId",projectId,table+"Id",id,"documentId",input.documentId(),"documentVersion",input.documentVersion(),"sha256",validation.get("sha256"),"description",input.description(),"linkedAt",Instant.now(),"linkedBy",caller.id());
        store.insert("evidence_link",row);save(table,item,map(),caller.id());itemChanged(project,caller,table,id,"UPDATED");return row;
    }
    public void removeEvidence(Caller caller,UUID projectId,UUID id,String table,UUID document,String match) {
        var project=project(caller,projectId,true);writer(project,caller,"DOCUMENT_UPLOAD");workEditable(project);var item=child(table,id,projectId,true);ProjectRules.etag(match,version(item));
        require(store.execute("delete from evidence_link where project_id=:project and "+itemTable(table)+"_id=:id and document_id=:document",map("project",projectId,"id",id,"document",document))==1,404,"RESOURCE_NOT_FOUND","The evidence link does not exist.");
        save(table,item,map(),caller.id());itemChanged(project,caller,table,id,"UPDATED");
    }
    public Map<String,Object> finalReport(Caller caller,UUID id) {return report(project(caller,id,false));}
    public Map<String,Object> putReport(Caller caller,UUID id,Report input,String match) {
        var project=project(caller,id,true);writer(project,caller,"REPORT_WRITE");var current=store.one("select * from final_report where project_id=:id",map("id",id));
        ProjectRules.etag(match,current.map(ProjectService::version).orElse(version(project)));
        if(current.isPresent())require(!"SUBMITTED".equals(current.get().get("status")),409,"REPORT_LOCKED","Reopen a submitted report before replacing it.");
        var validation=remote.validateDocument(id,input.documentId(),input.documentVersion(),"FINAL_REPORT",caller);
        var changes=map("documentId",input.documentId(),"documentVersion",input.documentVersion(),"sha256",validation.get("sha256"),"status","DRAFT");
        Map<String,Object> row;
        if(current.isPresent()){row=current.get();save("final_report",row,changes,caller.id());}
        else {row=created(caller.id());row.put("projectId",id);row.putAll(changes);store.insert("final_report",row);}
        save("project",project,map(),caller.id());events.write("ProjectChanged",project,caller,map("projectId",id));return row;
    }
    public Map<String,Object> submitReport(Caller caller,UUID id,String match) {
        var project=project(caller,id,true);writer(project,caller,"REPORT_WRITE");var report=report(project);ProjectRules.etag(match,version(report));
        require(Set.of("DRAFT","REOPENED").contains(text(report,"status")),409,"REPORT_LOCKED","Only a draft or reopened report can be submitted.");
        var validation=remote.validateDocument(id,(UUID)report.get("documentId"),((Number)report.get("documentVersion")).intValue(),"FINAL_REPORT",caller);
        require(Objects.equals(validation.get("sha256"),report.get("sha256")),409,"DOCUMENT_CHANGED","The validated document checksum has changed.");
        save("final_report",report,map("status","SUBMITTED","submittedAt",Instant.now(),"submittedBy",caller.id()),caller.id());save("project",project,map(),caller.id());
        events.write("FinalReportSubmitted",project,caller,map("projectId",id,"documentId",report.get("documentId"),"documentVersion",report.get("documentVersion"),"submittedBy",caller.id(),"submittedAt",report.get("submittedAt")));return report;
    }
    public Map<String,Object> reopenReport(Caller caller,UUID id,Reason input,String match) {
        var project=project(caller,id,true);require(caller.role("COORDINATOR")&&Objects.equals(project.get("coordinatorUserId"),caller.id()),403,"FORBIDDEN","The assigned coordinator must reopen the report.");ProjectRules.editable(text(project,"status"));
        var report=report(project);ProjectRules.etag(match,version(report));require("SUBMITTED".equals(report.get("status")),409,"REPORT_NOT_SUBMITTED","Only submitted reports can be reopened.");
        save("final_report",report,map("status","REOPENED","reopenedAt",Instant.now(),"reopenedBy",caller.id(),"reopeningReason",input.reason()),caller.id());
        save("project",project,map(),caller.id());events.write("ProjectChanged",project,caller,map("projectId",id));return report;
    }
    public Page<Map<String,Object>> publications(Caller caller,UUID id,int limit,String cursor) {
        project(caller,id,false);var page=collection("publication",id,Map.of(),limit,cursor,caller.id()+":publications:"+id);page.items().forEach(this::authors);return page;
    }
    public Map<String,Object> publication(Caller caller,UUID project,UUID id) {project(caller,project,false);var row=child("publication",id,project,false);authors(row);return row;}
    public Map<String,Object> addPublication(Caller caller,UUID id,PublicationInput input,String match) {
        var project=project(caller,id,true);writer(project,caller,"PUBLICATION_WRITE");ProjectRules.etag(match,version(project));validatePublication(input.title(),input.doi(),input.url(),input.authors());
        // An optional attachment is authorized using Document's own user-facing metadata endpoint.
        if(input.documentId()!=null)remote.publicationDocument(id,input.documentId(),caller);
        var row=created(caller.id());row.putAll(map("projectId",id,"title",input.title().trim(),"publicationType",input.type(),"doi",normalizeDoi(input.doi()),"publicationDate",input.publicationDate(),"url",input.url(),"journalOrVenue",input.journalOrVenue(),"documentId",input.documentId()));
        store.insert("publication",row);replaceAuthors((UUID)row.get("id"),input.authors());authors(row);save("project",project,map(),caller.id());
        events.write("PublicationRegistered",project,caller,map("projectId",id,"publicationId",row.get("id")));return row;
    }
    public Map<String,Object> patchPublication(Caller caller,UUID projectId,UUID id,PublicationPatch input,String match) {
        var project=project(caller,projectId,true);writer(project,caller,"PUBLICATION_WRITE");var row=child("publication",id,projectId,true);ProjectRules.etag(match,version(row));validatePublication(input.title(),input.doi(),input.url(),input.authors());
        if(input.documentId()!=null)remote.publicationDocument(projectId,input.documentId(),caller);
        var changes=map();put(changes,"title",input.title());put(changes,"publicationType",input.type());put(changes,"doi",input.doi()==null?null:normalizeDoi(input.doi()));put(changes,"publicationDate",input.publicationDate());put(changes,"url",input.url());put(changes,"journalOrVenue",input.journalOrVenue());put(changes,"documentId",input.documentId());
        if(input.doi()!=null&&input.doi().isBlank())changes.put("doi",null);
        save("publication",row,changes,caller.id());if(input.authors()!=null)replaceAuthors(id,input.authors());authors(row);save("project",project,map(),caller.id());events.write("ProjectChanged",project,caller,map("projectId",projectId));return row;
    }
    public void deletePublication(Caller caller,UUID projectId,UUID id,String match) {
        var project=project(caller,projectId,true);writer(project,caller,"PUBLICATION_WRITE");var row=child("publication",id,projectId,true);ProjectRules.etag(match,version(row));
        store.execute("delete from publication where id=:id",map("id",id));save("project",project,map(),caller.id());events.write("ProjectChanged",project,caller,map("projectId",projectId));
    }
    private void validatePublication(String title,String doi,String url,List<String> authors) {
        if(title!=null)ProjectRules.text(title);
        if(doi!=null&&!doi.isBlank())require(normalizeDoi(doi).matches("10\\.\\d{4,9}/\\S+"),422,"INVALID_DOI","Supply a DOI in 10.xxxx/... form or its doi.org URL.");
        if(url!=null&&!url.isBlank()){var parsed=URI.create(url);require(Set.of("https","http").contains(parsed.getScheme())&&parsed.getHost()!=null,422,"INVALID_URL","Publication URLs must be HTTP(S).");}
        if(authors!=null)require(authors.stream().allMatch(a->!a.isBlank())&&new HashSet<>(authors).size()==authors.size(),422,"INVALID_AUTHORS","Authors must be nonblank and distinct.");
    }
    private String normalizeDoi(String doi) {return doi==null||doi.isBlank()?null:doi.trim().replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/","").toLowerCase(Locale.ROOT);}
    private void replaceAuthors(UUID publication,List<String> authors) {
        store.execute("delete from publication_author where publication_id=:id",map("id",publication));int index=0;
        for(String author:authors)store.insert("publication_author",map("id",UUID.randomUUID(),"publicationId",publication,"displayName",author.trim(),"displayOrder",index++));
    }
    private void authors(Map<String,Object> publication) {publication.put("authors",store.query("select display_name from publication_author where publication_id=:id order by display_order",map("id",publication.get("id"))).stream().map(a->a.get("displayName")).toList());}
    /** Local facts only: these methods must never call Identity and create an authorization cycle. */
    public Map<String,Object> accessContext(UUID id,UUID subject) {
        var project=store.row("project",id);var member=store.one("select * from project_member where project_id=:id and user_id=:user",map("id",id,"user",subject));
        boolean active=member.isPresent()&&"ACTIVE".equals(member.get().get("status"));
        return map("projectId",id,"status",project.get("status"),"classification",project.get("classification"),"accessVersion",project.get("accessVersion"),
            "subject",map("userId",subject,"membershipStatus",active?"ACTIVE":member.isPresent()?"REMOVED":"NONE","projectRole",active?member.get().get("projectRole"):null,"permissions",active?effective(id,subject,member.get().get("projectRole").toString()):List.of()));
    }
    public Map<String,Object> contexts(AccessBatch input) {
        require(new HashSet<>(input.projectIds()).size()==input.projectIds().size(),400,"DUPLICATE_PROJECT","Project IDs must be distinct.");
        var items=new ArrayList<Map<String,Object>>();var missing=new ArrayList<UUID>();
        for(UUID id:input.projectIds()){if(store.one("select id from project where id=:id",map("id",id)).isEmpty())missing.add(id);else items.add(accessContext(id,input.subjectId()));}
        return map("items",items,"missingIds",missing);
    }
    public Map<String,Object> childContext(String table,UUID id,UUID subject) {
        var item=store.row(itemTable(table),id);var project=store.row("project",(UUID)item.get("projectId"));
        return map(table+"Id",id,"projectId",project.get("id"),"accessVersion",project.get("accessVersion"));
    }
    public Map<String,Object> association(UUID id,Association input) {
        var project=store.row("project",id);boolean valid=!Set.of("CLOSED","CANCELLED").contains(text(project,"status"));
        if(input.activityId()!=null)valid=valid&&store.one("select id from activity where id=:item and project_id=:id and status<>'CANCELLED'",map("item",input.activityId(),"id",id)).isPresent();
        if(input.milestoneId()!=null)valid=valid&&store.one("select id from milestone where id=:item and project_id=:id and status<>'CANCELLED'",map("item",input.milestoneId(),"id",id)).isPresent();
        if(input.activityId()!=null&&input.milestoneId()!=null)valid=valid&&store.one("select id from activity where id=:item and project_id=:id and milestone_id=:milestone",map("item",input.activityId(),"id",id,"milestone",input.milestoneId())).isPresent();
        if(input.purpose()==Purpose.EVIDENCE)valid=valid&&(input.activityId()!=null||input.milestoneId()!=null);
        return map("valid",valid,"reasonCode",valid?"VALID":"INVALID_PROJECT_ASSOCIATION","projectVersion",project.get("version"));
    }
    public long projectVersion(Caller caller,UUID id) {return version(project(caller,id,false));}
    private Map<String,Object> project(Caller caller,UUID id,boolean lock) {var project=store.row("project",id,lock);access(project,caller);return project;}
    private void access(Map<String,Object> project,Caller caller) {
        boolean privileged=caller.role("ADMIN")||(caller.role("COORDINATOR")&&Objects.equals(project.get("coordinatorUserId"),caller.id()));
        boolean member=active((UUID)project.get("id"),caller.id()).isPresent();
        if(Set.of("CONFIDENTIAL","RESTRICTED").contains(text(project,"classification")))privileged=privileged&&member;
        require(privileged||member,404,"RESOURCE_NOT_FOUND","The project is absent or not visible.");
    }
    private Optional<Map<String,Object>> active(UUID project,UUID user) {return store.one("select * from project_member where project_id=:id and user_id=:user and status='ACTIVE'",map("id",project,"user",user));}
    private Map<String,Object> memberView(UUID project,UUID user) {
        var member=active(project,user).orElseThrow(()->new ApiException(404,"MEMBER_NOT_FOUND","The user is not an active project member."));
        member.put("permissions",permissions(project,user));return member;
    }
    private List<String> permissions(UUID project,UUID user) {return store.query("select permission_code from project_member_permission where project_id=:project and user_id=:user order by permission_code",map("project",project,"user",user)).stream().map(row->text(row,"permissionCode")).toList();}
    private Set<String> effective(UUID project,UUID user,String role) {
        var values=new TreeSet<>(permissions(project,user));values.add("PROJECT_READ");values.add("DOCUMENT_READ");
        if(Set.of("PRINCIPAL_RESEARCHER","RESEARCHER").contains(role))values.addAll(Set.of("PROJECT_WRITE","ACTIVITY_WRITE","MILESTONE_WRITE","DOCUMENT_UPLOAD","REPORT_WRITE","PUBLICATION_WRITE"));
        return values;
    }
    private boolean canWrite(Map<String,Object> project,Caller caller,String permission) {
        if(caller.role("ADMIN")||(caller.role("COORDINATOR")&&Objects.equals(project.get("coordinatorUserId"),caller.id())))return true;
        UUID id=(UUID)project.get("id");return active(id,caller.id()).map(member->effective(id,caller.id(),text(member,"projectRole")).contains(permission)).orElse(false);
    }
    private void writer(Map<String,Object> project,Caller caller,String permission) {
        ProjectRules.editable(text(project,"status"));require(canWrite(project,caller,permission),403,"FORBIDDEN","The required project permission is missing.");
    }
    private void manage(Map<String,Object> project,Caller caller) {
        ProjectRules.editable(text(project,"status"));require(caller.role("ADMIN")||(caller.role("COORDINATOR")&&Objects.equals(project.get("coordinatorUserId"),caller.id()))
            ||Objects.equals(project.get("principalResearcherId"),caller.id()),403,"FORBIDDEN","A principal researcher, assigned coordinator or administrator is required.");
    }
    private void assigneeOrWriter(Map<String,Object> project,Map<String,Object> activity,Caller caller) {
        ProjectRules.editable(text(project,"status"));require(canWrite(project,caller,"ACTIVITY_WRITE")||(Objects.equals(activity.get("responsibleUserId"),caller.id())&&active((UUID)project.get("id"),caller.id()).isPresent()),403,"FORBIDDEN","Only an authorized researcher or the active assignee can change this activity.");
    }
    private void workEditable(Map<String,Object> project) {require(Set.of("APPROVED","ACTIVE","DELAYED").contains(text(project,"status")),409,"PROJECT_WORK_LOCKED","Reopen a completed project before changing its work items.");}
    private Map<String,Object> child(String table,UUID id,UUID project,boolean lock) {
        var row=store.row(table,id,lock);require(project.equals(row.get("projectId")),404,"RESOURCE_NOT_FOUND","The resource is absent from this project.");return row;
    }
    private Map<String,Object> report(Map<String,Object> project) {return store.one("select * from final_report where project_id=:id",map("id",project.get("id"))).orElseThrow(()->new ApiException(404,"FINAL_REPORT_NOT_FOUND","No final report is associated with this project."));}
    private void save(String table,Map<String,Object> row,Map<String,Object> changes,UUID actor) {
        changes=new LinkedHashMap<>(changes);changes.put("version",version(row)+1);changes.put("updatedAt",Instant.now());changes.put("updatedBy",actor);
        store.update(table,(UUID)row.get("id"),changes);row.putAll(changes);
    }
    private Map<String,Object> created(UUID actor) {var now=Instant.now();return map("id",UUID.randomUUID(),"createdAt",now,"createdBy",actor,"updatedAt",now,"updatedBy",actor,"version",1L);}
    private void history(Map<String,Object> project,String from,String reason,UUID actor) {store.insert("project_status_history",map("id",UUID.randomUUID(),"projectId",project.get("id"),"fromStatus",from,"toStatus",project.get("status"),"reason",reason,"changedAt",Instant.now(),"changedBy",actor,"correlationId",DomainEvents.correlation()));}
    private void itemChanged(Map<String,Object> project,Caller caller,String table,UUID item,String operation) {
        save("project",project,map(),caller.id());recalculate(project,caller);
        events.write(table.equals("activity")?"ActivityChanged":"MilestoneChanged",project,caller,map("projectId",project.get("id"),table+"Id",item,"operation",operation));
    }
    private ProjectRules.Progress calculation(Map<String,Object> project) {
        String table=text(project,"progressMethod").equals("ACTIVITY_WEIGHT")?"activity":"milestone";
        var rows=store.query("select id,status,weight from "+table+" where project_id=:id order by id",map("id",project.get("id")));
        return ProjectRules.progress(rows.stream().map(row->new ProjectRules.Contribution((UUID)row.get("id"),text(row,"status"),(BigDecimal)row.get("weight"))).toList());
    }
    private Map<String,Object> progressView(Map<String,Object> project) {var progress=calculation(project);return map("projectId",project.get("id"),"version",project.get("version"),"calculationMethod",project.get("progressMethod"),"progressPercent",progress.percent(),"completedWeight",progress.completedWeight(),"totalWeight",progress.totalWeight(),"contributions",progress.contributions());}
    private void recalculate(Map<String,Object> project,Caller caller) {
        var progress=progressView(project);BigDecimal value=(BigDecimal)progress.get("progressPercent");boolean changed=value.compareTo(new BigDecimal(project.get("progressPercent").toString()))!=0;
        store.update("project",(UUID)project.get("id"),map("progressPercent",value));project.put("progressPercent",value);
        store.insert("project_progress_snapshot",map("id",UUID.randomUUID(),"projectId",project.get("id"),"progressPercent",value,"calculationMethod",project.get("progressMethod"),"calculationDetails",progress,"calculatedAt",Instant.now()));
        if(changed)events.write("ProjectProgressChanged",project,caller,map("projectId",project.get("id"),"progress",value));
    }
    private Page<Map<String,Object>> collection(String table,UUID project,Map<String,?> extra,int limit,String cursor,String binding) {
        require(Set.of("project_status_history","evidence_link","publication").contains(table),400,"INVALID_COLLECTION","Unknown collection.");
        var after=CursorPages.after(cursor,binding,limit);var parameters=map("project",project,"after",after,"limit",limit+1);var sql=new StringBuilder("select * from "+table+" where project_id=:project and id>:after");
        extra.forEach((key,value)->{if(!Set.of("activity_id","milestone_id").contains(key))throw new IllegalArgumentException();sql.append(" and ").append(key).append("=:filter");parameters.put("filter",value);});
        sql.append(" order by id limit :limit");return CursorPages.page(store.query(sql.toString(),parameters),binding,limit);
    }
    private Map<String,Object> view(Map<String,Object> project) {var view=new LinkedHashMap<>(project);view.remove("approvedSnapshot");view.put("coordinatorId",view.remove("coordinatorUserId"));return view;}
    private static void sorting(StringBuilder sql,Map<String,Object> parameters,String sort,Map<String,String> fields,String prefix,Map<String,Object> anchor) {
        boolean descending=sort.startsWith("-");String field=descending?sort.substring(1):sort;
        require(fields.containsKey(field),400,"INVALID_SORT","The requested sort field is not supported.");
        String column=prefix+fields.get(field),id=prefix+"id",direction=descending?" desc":" asc",comparison=descending?"<":">";
        if(anchor!=null) {
            parameters.put("sortValue",anchor.get(field));parameters.put("sortId",anchor.get("id"));
            sql.append(" and (").append(column).append(",").append(id).append(")").append(comparison).append("(:sortValue,:sortId)");
        }
        sql.append(" order by ").append(column).append(direction).append(",").append(id).append(direction);
    }
    private static void range(Map<String,String> filters) {
        if(filters.containsKey("deadlineFrom")&&filters.containsKey("deadlineTo"))require(!LocalDate.parse(filters.get("deadlineFrom")).isAfter(LocalDate.parse(filters.get("deadlineTo"))),400,"INVALID_DATE_RANGE","deadlineFrom must be on or before deadlineTo.");
    }
    private static void dateFilter(StringBuilder sql,Map<String,Object> parameters,Map<String,String> filters,String name,String column,String operation) {if(filters.containsKey(name)){sql.append(" and ").append(column).append(operation).append(":").append(name);parameters.put(name,LocalDate.parse(filters.get(name)));}}
    private static String escape(String text){return text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static void put(Map<String,Object> values,String key,Object value){if(value!=null)values.put(key,value);}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static long version(Map<String,Object> row){return number(row,"version");}
    private static BigDecimal weight(BigDecimal value){return value==null?BigDecimal.ONE:value;}
    private static String itemTable(String table){require(Set.of("activity","milestone").contains(table),400,"INVALID_ITEM","Unknown work item type.");return table;}
}
