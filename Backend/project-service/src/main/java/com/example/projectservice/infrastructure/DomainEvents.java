package com.example.projectservice.infrastructure;

import com.example.projectservice.api.ApiModels.Caller;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import java.time.Instant;
import java.util.*;
import static com.example.projectservice.infrastructure.ProjectStore.map;

@Component
public class DomainEvents {
    private static final ThreadLocal<Origin> ORIGIN=new ThreadLocal<>();
    private record Origin(String correlation,UUID causation) { }
    public static void fromEvent(Map<String,Object> event,Runnable action) {
        var old=ORIGIN.get();ORIGIN.set(new Origin(event.get("correlationId").toString(),UUID.fromString(event.get("eventId").toString())));
        try{action.run();}finally{if(old==null)ORIGIN.remove();else ORIGIN.set(old);}
    }
    private final ProjectStore store;
    public DomainEvents(ProjectStore store){this.store=store;}
    public static String correlation() {
        if(ORIGIN.get()!=null)return ORIGIN.get().correlation();
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            var value=attrs.getRequest().getAttribute("correlationId");if(value!=null)return value.toString();
        }return UUID.randomUUID().toString();
    }
    public UUID write(String type,Map<String,Object> project,Caller caller,Map<String,?> data) {
        return write(type,project,caller==null?"SERVICE":"USER",caller==null?"project-execution-service":caller.id().toString(),data);
    }
    public UUID write(String type,Map<String,Object> project,String actorType,String actor,Map<String,?> data) {
        var id=UUID.randomUUID();var now=Instant.now();String correlation=correlation();
        var envelope=map("eventId",id,"eventType",type,"eventVersion",1,"occurredAt",now,"producer","project-execution-service",
            "correlationId",correlation,"actor",map("type",actorType,"id",actor),
            "aggregate",map("type","PROJECT","id",project.get("id"),"version",project.get("version")),"data",data);
        if(ORIGIN.get()!=null)envelope.put("causationId",ORIGIN.get().causation());
        store.insert("outbox_event",map("id",id,"aggregateType","PROJECT","aggregateId",project.get("id"),"eventType",type,
            "eventVersion",1,"payload",envelope,"occurredAt",now,"correlationId",correlation,"actorId",actor));return id;
    }
}
