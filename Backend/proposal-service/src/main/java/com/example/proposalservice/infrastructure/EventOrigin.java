package com.example.proposalservice.infrastructure;

import java.util.*;

public final class EventOrigin {
    private record Origin(String correlation,UUID causation) {}
    private static final ThreadLocal<Origin> CURRENT=new ThreadLocal<>();
    private EventOrigin(){}
    public static String correlation(){return CURRENT.get()==null?null:CURRENT.get().correlation();}
    public static UUID causation(){return CURRENT.get()==null?null:CURRENT.get().causation();}
    public static void run(Map<String,Object> event,Runnable work) {
        var old=CURRENT.get();CURRENT.set(new Origin(event.get("correlationId").toString(),UUID.fromString(event.get("eventId").toString())));
        try{work.run();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}
    }
}
