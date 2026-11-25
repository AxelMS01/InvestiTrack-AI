package com.example.identityservice.infrastructure;

import com.example.identityservice.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Component
public class DomainEvents {
    private final IdentityRepository repo;
    private final ObjectMapper json;
    public DomainEvents(IdentityRepository repo, ObjectMapper json) { this.repo=repo; this.json=json; }
    public void user(String type, User user, String actorType, String actor) {
        var data = new LinkedHashMap<String,Object>();
        data.put("userId",user.id()); data.put("active",user.active()); data.put("identityVersion",user.version());
        if(type.equals("UserRolesChanged")) data.put("roles",repo.roles(user.id()));
        write(type,"USER",user.id(),user.version(),actorType,actor,data);
    }
    public void write(String type, String aggregateType, UUID aggregateId, long version, String actorType, String actor, Map<String,?> data) {
        var id=UUID.randomUUID(); var now=Instant.now();
        var attrs=RequestContextHolder.getRequestAttributes();
        String correlation=attrs instanceof ServletRequestAttributes s ? (String)s.getRequest().getAttribute("correlationId") : UUID.randomUUID().toString();
        if(correlation==null) correlation=UUID.randomUUID().toString();
        var envelope=Map.of("eventId",id,"eventType",type,"eventVersion",1,"occurredAt",now,"producer","identity-service",
            "correlationId",correlation,"actor",Map.of("type",actorType,"id",actor),
            "aggregate",Map.of("type",aggregateType,"id",aggregateId,"version",version),"data",data);
        repo.db().sql("""
            insert into outbox_event(id,aggregate_type,aggregate_id,event_type,event_version,payload,occurred_at,correlation_id,actor_id)
            values(:id,:at,:aid,:et,1,cast(:p as jsonb),:t,:c,:a)
            """).param("id",id).param("at",aggregateType).param("aid",aggregateId).param("et",type)
            .param("p",json.writeValueAsString(envelope)).param("t",java.sql.Timestamp.from(now)).param("c",correlation).param("a",actor).update();
    }
}
