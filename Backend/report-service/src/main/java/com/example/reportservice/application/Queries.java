package com.example.reportservice.application;

import com.example.reportservice.api.ApiModels.Caller;
import com.example.reportservice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.example.reportservice.api.ApiException.*;
import static com.example.reportservice.infrastructure.ReportStore.map;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class Queries {
    private final ReportStore store;
    private final RemoteServices remote;
    public Queries(ReportStore store,RemoteServices remote){this.store=store;this.remote=remote;}
    public Set<UUID> scope(Caller caller,String type) {
        String field=type.equals("PROJECT")?"projectId":"proposalId";
        var rows=store.query("select distinct state->>'"+field+"' as id from projection_state where not deleted and state->>'"+field+"' is not null",Map.of());
        return remote.allowed(caller,type,rows.stream().map(r->UUID.fromString(r.get("id").toString())).toList());
    }
    public void project(Caller caller,UUID id){require(remote.allowed(caller,"PROJECT",List.of(id)).contains(id),404,"RESOURCE_NOT_FOUND","The project is absent or not visible.");}
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> rows(String kind,Set<UUID> ids,Map<String,String> filters) {
        if(ids.isEmpty())return List.of();
        String types=switch(kind){case "PROJECTS"->"'PROJECT'";case "DEADLINES"->"'ACTIVITY','MILESTONE'";case "EVALUATIONS"->"'EVALUATOR_ASSIGNMENT'";default->throw new IllegalArgumentException();};
        String field=kind.equals("EVALUATIONS")?"proposalId":"projectId";
        var parameters=map("ids",ids.stream().map(UUID::toString).toList());
        var sql=new StringBuilder("select state,received_at from projection_state where not deleted and aggregate_type in ("+types+") and state->>'"+field+"' in (:ids)");
        var fields=Map.of("researchArea","researchAreaCode","status","status","coordinatorId","coordinatorUserId","responsibleUserId","responsibleUserId","projectId","projectId","type","resourceType","evaluatorUserId","evaluatorUserId");
        fields.forEach((input,column)->{if(kind.equals("EVALUATIONS")&&input.equals("status"))column="proposalStatus";if(filters.containsKey(input)){sql.append(" and state->>'").append(column).append("' = :").append(input);parameters.put(input,filters.get(input));}});
        String date=kind.equals("PROJECTS")?"plannedEndDate":kind.equals("EVALUATIONS")?"dueDate":"deadline";
        for(String bound:List.of("from","to"))if(filters.containsKey(bound)) {
            LocalDate.parse(filters.get(bound));sql.append(" and (state->>'").append(date).append("')::date ").append(bound.equals("from")?">=":"<=").append(" cast(:").append(bound).append(" as date)");parameters.put(bound,filters.get(bound));
        }
        if(filters.containsKey("from")&&filters.containsKey("to"))require(filters.get("from").compareTo(filters.get("to"))<=0,400,"INVALID_DATE_RANGE","from must not exceed to.");
        sql.append(" order by aggregate_type,aggregate_id limit 10001");
        var result=new ArrayList<Map<String,Object>>();
        var source=store.query(sql.toString(),parameters);
        require(source.size()<=10000,422,"RESULT_TOO_LARGE","Narrow the query to at most 10000 records.");
        for(var row:source) {
            var state=new LinkedHashMap<>((Map<String,Object>)row.get("state"));
            if(kind.equals("DEADLINES")) {
                String current=com.example.reportservice.domain.DeadlineState.at(state.get("status").toString(),LocalDate.parse(state.get("deadline").toString()),LocalDate.now(ZoneOffset.UTC));
                state.put("deadlineState",current);
                if(filters.containsKey("state")&&!filters.get("state").equals(current))continue;
            }
            result.add(state);
        }
        require(result.size()<=10000,422,"RESULT_TOO_LARGE","Narrow the query to at most 10000 records.");return result;
    }
    public Map<String,Object> dashboard(Caller caller,String view,UUID project,Map<String,String> filters) {
        if(view.equals("coordinator"))require(caller.has("COORDINATOR"),403,"FORBIDDEN","Coordinator role required.");
        if(view.equals("researcher"))require(caller.has("RESEARCHER","STUDENT"),403,"FORBIDDEN","Researcher or student role required.");
        if(view.equals("evaluator"))require(caller.has("EVALUATOR"),403,"FORBIDDEN","Evaluator role required.");
        require(!filters.containsKey("coordinatorId")||caller.has("COORDINATOR"),403,"FORBIDDEN","Coordinator filtering requires a coordinator role.");
        if(view.equals("evaluator")) {
            var f=new HashMap<>(filters);f.put("evaluatorUserId",caller.id().toString());
            var assignments=rows("EVALUATIONS",scope(caller,"PROPOSAL"),f);
            return map("asOf",asOf(),"assignments",assignments,"counts",map("pendingEvaluations",pending(assignments),"completedEvaluations",assignments.stream().filter(a->"COMPLETED".equals(a.get("assignmentStatus"))).count()));
        }
        Set<UUID> ids;if(project!=null){project(caller,project);ids=Set.of(project);}else ids=scope(caller,"PROJECT");
        if(view.equals("researcher")) {
            // Researcher dashboard is the caller's projects, while live scope remains authoritative.
            var mine=store.query("select aggregate_id as id from projection_state where aggregate_type='PROJECT' and not deleted and (state->>'principalResearcherId'=:user or jsonb_exists(state->'activeMemberIds',:user))",map("user",caller.id().toString()));
            var members=new HashSet<UUID>();mine.forEach(r->members.add((UUID)r.get("id")));ids=new HashSet<>(ids);ids.retainAll(members);
        }
        var projects=rows("PROJECTS",ids,filters);
        var selected=new HashSet<UUID>();projects.forEach(p->selected.add(UUID.fromString(p.get("projectId").toString())));
        var deadlines=rows("DEADLINES",selected,Map.of());
        var grouped=new TreeMap<String,Long>();projects.forEach(p->grouped.merge(p.get("status").toString(),1L,Long::sum));
        var evaluationFilters=new HashMap<String,String>();
        for(String field:List.of("researchArea","from","to"))if(filters.containsKey(field))evaluationFilters.put(field,filters.get(field));
        var evaluations= view.equals("coordinator")?rows("EVALUATIONS",scope(caller,"PROPOSAL"),evaluationFilters):List.<Map<String,Object>>of();
        double progress=projects.stream().mapToDouble(p->((Number)p.get("progressPercent")).doubleValue()).average().orElse(0);
        return map("asOf",asOf(),"counts",map("activeProjects",grouped.getOrDefault("ACTIVE",0L),"delayedProjects",grouped.getOrDefault("DELAYED",0L),
            "upcomingMilestones",deadlines.stream().filter(d->"MILESTONE".equals(d.get("resourceType"))&&"UPCOMING".equals(d.get("deadlineState"))).count(),
            "completedMilestones",deadlines.stream().filter(d->"MILESTONE".equals(d.get("resourceType"))&&"COMPLETED".equals(d.get("status"))).count(),"pendingEvaluations",pending(evaluations)),
            "projectsByStatus",grouped.entrySet().stream().map(e->Map.of("status",e.getKey(),"count",e.getValue())).toList(),"averageProgressPercent",progress,
            "projects",projects,"upcomingDeadlines",deadlines.stream().filter(d->Set.of("UPCOMING","OVERDUE").contains(d.get("deadlineState"))).sorted(Comparator.comparing(d->d.get("deadline").toString())).limit(100).toList());
    }
    private long pending(List<Map<String,Object>> rows){return rows.stream().filter(a->Set.of("ASSIGNED","ACCEPTED","IN_PROGRESS","PENDING").contains(a.get("assignmentStatus"))).count();}
    public Object asOf(){return store.one("select max(received_at) as as_of from projection_state",Map.of()).orElseThrow().get("asOf");}
    public Map<String,Object> deadlines(Caller caller,UUID project,Map<String,String> filters) {
        if(project!=null){project(caller,project);require(!filters.containsKey("projectId")||project.toString().equals(filters.get("projectId")),400,"SCOPE_MISMATCH","Conflicting project filters.");}
        var rows=rows("DEADLINES",project==null?scope(caller,"PROJECT"):Set.of(project),filters);
        rows.sort(Comparator.comparing((Map<String,Object> d)->d.get("deadline").toString()).thenComparing(d->d.get("resourceType").toString()).thenComparing(d->d.get("resourceId").toString()));
        return page(rows,filters,"resourceId");
    }
    private String pageKey(Map<String,Object> row,String key) {
        return key.equals("resourceId")?row.get("deadline")+"|"+row.get("resourceType")+"|"+row.get(key):row.get(key).toString();
    }
    public Map<String,Object> page(List<Map<String,Object>> rows,Map<String,String> filters,String key) {
        int limit=Integer.parseInt(filters.getOrDefault("limit","20"));require(limit>0&&limit<=100,400,"INVALID_LIMIT","limit must be between 1 and 100.");
        String binding=filters.entrySet().stream().filter(e->!e.getKey().equals("cursor")&&!e.getKey().equals("limit")).sorted(Map.Entry.comparingByKey()).toList().toString();
        String after="";if(filters.containsKey("cursor")) {
            try{var parts=new String(Base64.getUrlDecoder().decode(filters.get("cursor")),StandardCharsets.UTF_8).split("\n",-1);require(parts.length==2&&parts[0].equals(binding),400,"INVALID_CURSOR","The cursor does not match this query.");after=parts[1];}
            catch(IllegalArgumentException e){throw new com.example.reportservice.api.ApiException(400,"INVALID_CURSOR","Invalid cursor.");}
        }
        int start=0;if(!after.isEmpty()){boolean found=false;for(int i=0;i<rows.size();i++)if(pageKey(rows.get(i),key).equals(after)){start=i+1;found=true;break;}require(found,400,"INVALID_CURSOR","The cursor is no longer available; restart the query.");}
        int end=Math.min(start+limit,rows.size());String next=end<rows.size()?Base64.getUrlEncoder().withoutPadding().encodeToString((binding+"\n"+pageKey(rows.get(end-1),key)).getBytes(StandardCharsets.UTF_8)):null;
        return map("asOf",asOf(),"items",rows.subList(start,end),"nextCursor",next,"limit",limit);
    }
}
