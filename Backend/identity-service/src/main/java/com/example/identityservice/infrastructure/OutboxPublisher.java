package com.example.identityservice.infrastructure;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
class MessagingConfiguration {
    @Bean
    @ConditionalOnProperty(name="identity.outbox.enabled",havingValue="true",matchIfMissing=true)
    TopicExchange domainExchange(@Value("${identity.outbox.exchange:invesctictrack.domain}") String name) {
        return new TopicExchange(name,true,false);
    }
}

@Component
@ConditionalOnProperty(name="identity.outbox.enabled",havingValue="true",matchIfMissing=true)
public class OutboxPublisher {
    private final IdentityRepository repo;
    private final RabbitTemplate rabbit;
    private final String exchange;
    public OutboxPublisher(IdentityRepository repo, RabbitTemplate rabbit,
        @Value("${identity.outbox.exchange:invesctictrack.domain}") String exchange) {
        this.repo=repo; this.rabbit=rabbit; this.exchange=exchange; rabbit.setMandatory(true);
    }
    @Scheduled(fixedDelayString="${identity.outbox.poll-ms:1000}")
    @Transactional
    public void publish() {
        var rows=repo.db().sql("""
            select id,event_type,cast(payload as text) as payload,attempt_count from outbox_event
            where published_at is null and (next_attempt_at is null or next_attempt_at<=:now)
            order by occurred_at,id limit 20 for update skip locked
            """).param("now",java.sql.Timestamp.from(Instant.now())).query((r,n)->new Pending(r.getObject("id",UUID.class),r.getString("event_type"),r.getString("payload"),r.getInt("attempt_count"))).list();
        for(var row:rows) {
            try {
                var props=new MessageProperties(); props.setContentType("application/json");
                props.setDeliveryMode(MessageDeliveryMode.PERSISTENT); props.setMessageId(row.id().toString());
                var correlation=new CorrelationData(row.id().toString());
                rabbit.send(exchange,"identity."+row.type()+".v1",new Message(row.payload().getBytes(StandardCharsets.UTF_8),props),correlation);
                var confirm=correlation.getFuture().get(2,TimeUnit.SECONDS);
                if(!confirm.ack()||correlation.getReturned()!=null) throw new IllegalStateException("Broker did not route and confirm event");
                repo.db().sql("update outbox_event set published_at=:now,attempt_count=attempt_count+1,next_attempt_at=null where id=:id")
                    .param("now",java.sql.Timestamp.from(Instant.now())).param("id",row.id()).update();
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                long delay=Math.min(300,1L<<Math.min(row.attempts()+1,8));
                repo.db().sql("update outbox_event set attempt_count=attempt_count+1,next_attempt_at=:next where id=:id")
                    .param("next",java.sql.Timestamp.from(Instant.now().plusSeconds(delay))).param("id",row.id()).update();
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Outbox delivery pending for event {}",row.id());
            }
        }
    }
    private record Pending(UUID id,String type,String payload,int attempts) {}
}
