package com.example.projectservice.application;

import com.example.projectservice.infrastructure.ProjectStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.example.projectservice.api.ApiException.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

@Service
@Transactional(isolation=Isolation.REPEATABLE_READ)
public class ProjectionService {
    private final ProjectStore store;
    public ProjectionService(ProjectStore store){this.store=store;}
    private static final String PROJECT="""
        select 'PROJECT' as aggregate_type,p.id as aggregate_id,p.version as aggregate_version,
        jsonb_build_object('projectId',p.id,'projectNumber',p.project_number,'title',p.title,'status',p.status,
        'classification',p.classification,'researchAreaCode',p.research_area_code,'coordinatorUserId',p.coordinator_user_id,
        'principalResearcherId',p.principal_researcher_id,'progressPercent',p.progress_percent,'startDate',p.start_date,
        'plannedEndDate',p.planned_end_date,'updatedAt',p.updated_at,'sourceVersion',p.version,
        'activeMemberIds',coalesce((select jsonb_agg(m.user_id order by m.user_id) from project_member m where m.project_id=p.id and m.status='ACTIVE'),'[]'::jsonb)) as state
        from project p
        """;
    private static String items(String table) {
        return "select '"+table.toUpperCase(Locale.ROOT)+"' as aggregate_type,a.id as aggregate_id,a.version as aggregate_version,"+
            "jsonb_build_object('resourceType','"+table.toUpperCase(Locale.ROOT)+"','resourceId',a.id,'projectId',a.project_id,'projectTitle',p.title,'resourceName',a.name,'deadline',a.deadline,'status',a.status,'updatedAt',a.updated_at,'sourceVersion',a.version,"+
            "'responsibleUserId',"+(table.equals("activity")?"a.responsible_user_id":"null")+","+
            "'deadlineState',case when a.status in ('COMPLETED','CANCELLED') then 'COMPLETED' when a.deadline<(now() at time zone 'UTC')::date then 'OVERDUE' when a.deadline<=(now() at time zone 'UTC')::date+7 then 'UPCOMING' else 'FUTURE' end) as state from "+table+" a join project p on p.id=a.project_id";
    }
    public Map<String,Object> state(String aggregate,UUID id) {
        require(Set.of("PROJECT","ACTIVITY","MILESTONE").contains(aggregate),400,"INVALID_AGGREGATE","Unsupported aggregate type.");
        String select=aggregate.equals("PROJECT")?PROJECT:items(aggregate.toLowerCase(Locale.ROOT));
        var found=store.one("select * from ("+select+") state where aggregate_id=:id",map("id",id));
        if(found.isPresent()){var row=found.get();row.put("deleted",false);return row;}
        var tombstone=store.one("select * from aggregate_tombstone where aggregate_type=:type and aggregate_id=:id",map("type",aggregate,"id",id));
        if(tombstone.isPresent()){var row=tombstone.get();return map("aggregateType",aggregate,"aggregateId",id,"aggregateVersion",row.get("version"),"state",map("projectId",row.get("projectId")),"deleted",true);}
        throw new com.example.projectservice.api.ApiException(404,"RESOURCE_NOT_FOUND","No aggregate or tombstone exists.");
    }
    public Map<String,Object> snapshot(String projection,String cursor,int limit) {
        require(Set.of("PROJECTS","DEADLINES").contains(projection),400,"INVALID_PROJECTION","Project supports PROJECTS and DEADLINES projections.");
        require(limit>=1&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");UUID id;long after=0;Instant asOf;
        if(cursor==null) {
            id=UUID.randomUUID();asOf=Instant.now();store.insert("projection_export",map("id",id,"projection",projection,"createdAt",asOf,"expiresAt",asOf.plus(Duration.ofMinutes(15))));
            String source=projection.equals("PROJECTS")?PROJECT:items("activity")+" union all "+items("milestone");
            store.execute("insert into projection_export_item(snapshot_id,ordinal,aggregate_type,aggregate_id,aggregate_version,state) select :id,row_number() over(order by aggregate_type,aggregate_id),aggregate_type,aggregate_id,aggregate_version,state from ("+source+") source",map("id",id));
        }else {
            try {require(cursor.length()<200,400,"INVALID_CURSOR","Invalid snapshot cursor.");var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":");require(parts.length==2,400,"INVALID_CURSOR","Invalid snapshot cursor.");id=UUID.fromString(parts[0]);after=Long.parseLong(parts[1]);require(after>=0,400,"INVALID_CURSOR","Invalid snapshot position.");}
            catch(RuntimeException e){throw new com.example.projectservice.api.ApiException(400,"INVALID_CURSOR","Invalid snapshot cursor.");}
            var export=store.one("select * from projection_export where id=:id and projection=:projection and expires_at>now()",map("id",id,"projection",projection)).orElseThrow(()->new com.example.projectservice.api.ApiException(409,"SNAPSHOT_EXPIRED","The snapshot expired or belongs to another projection. Restart the rebuild."));
            asOf=(Instant)export.get("createdAt");
        }
        var rows=store.query("select * from projection_export_item where snapshot_id=:id and ordinal>:after order by ordinal limit :limit",map("id",id,"after",after,"limit",limit+1));
        boolean more=rows.size()>limit;var items=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((id+":"+items.getLast().get("ordinal")).getBytes(StandardCharsets.UTF_8)):null;
        items.forEach(row->{row.remove("ordinal");row.remove("snapshotId");});return map("snapshotId",id,"asOf",asOf,"items",items,"nextCursor",next);
    }
}
