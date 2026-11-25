package com.example.documentservice.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import java.time.Instant;
import java.util.*;
import static com.example.documentservice.infrastructure.DocumentStore.map;

@Component
public class DomainEvents {
    private final DocumentStore store;
    public DomainEvents(DocumentStore store){this.store=store;}
    public static String correlation(){if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a){var c=a.getRequest().getAttribute("correlationId");if(c!=null)return c.toString();}return UUID.randomUUID().toString();}
    public void write(String type,Map<String,Object> document,String actor,Map<String,Object> data) {
        UUID id=UUID.randomUUID();Instant now=Instant.now();String correlation=correlation();
        var envelope=map("eventId",id,"eventType",type,"eventVersion",1,"occurredAt",now,"producer","document-service","correlationId",correlation,
            "actor",map("type",actor.equals("document-service")?"SERVICE":"USER","id",actor),"aggregate",map("type","DOCUMENT","id",document.get("id"),"version",document.get("version")),"data",data);
        store.insert("outbox_event",map("id",id,"aggregateType","DOCUMENT","aggregateId",document.get("id"),"eventType",type,"eventVersion",1,"payload",envelope,"occurredAt",now,"correlationId",correlation,"actorId",actor));
    }
}
