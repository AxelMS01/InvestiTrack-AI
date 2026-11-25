package com.example.documentservice.infrastructure;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.example.documentservice.infrastructure.DocumentStore.map;

@Component
@ConditionalOnProperty(name="document.messaging.enabled",havingValue="true")
public class OutboxPublisher {
    private final DocumentStore store;
    private final RabbitTemplate rabbit;
    public OutboxPublisher(DocumentStore store,RabbitTemplate rabbit){this.store=store;this.rabbit=rabbit;rabbit.setMandatory(true);}
    @Scheduled(fixedDelayString="${document.outbox.poll-ms:1000}") @Transactional
    public void publish() {
        var rows=store.query("select id,event_type,payload::text as body,attempt_count from outbox_event where published_at is null and (next_attempt_at is null or next_attempt_at<=now()) order by occurred_at,id limit 20 for update skip locked",Map.of());
        for(var row:rows) {
            try {
                var properties=new MessageProperties();properties.setContentType("application/json");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(row.get("id").toString());
                var confirmation=new CorrelationData(row.get("id").toString());
                rabbit.send("invesctictrack.domain","document."+row.get("eventType")+".v1",new Message(row.get("body").toString().getBytes(StandardCharsets.UTF_8),properties),confirmation);
                var result=confirmation.getFuture().get(2,TimeUnit.SECONDS);
                if(!result.ack()||confirmation.getReturned()!=null)throw new IllegalStateException("Event was not routed and confirmed");
                store.execute("update outbox_event set published_at=now(),attempt_count=attempt_count+1,next_attempt_at=null where id=:id",map("id",row.get("id")));
            }catch(Exception e) {
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                long delay=Math.min(600,1L<<Math.min(((Number)row.get("attemptCount")).intValue()+1,9));
                store.execute("update outbox_event set attempt_count=attempt_count+1,next_attempt_at=:next where id=:id",map("id",row.get("id"),"next",Instant.now().plusSeconds(delay)));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document event {} remains pending delivery",row.get("id"));
            }
        }
    }
}
