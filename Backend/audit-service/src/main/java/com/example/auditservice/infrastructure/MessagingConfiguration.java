package com.example.auditservice.infrastructure;

import com.example.auditservice.api.ApiException;
import com.example.auditservice.application.Ingestion;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.io.IOException;
import java.util.ArrayList;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
    @Configuration
    @ConditionalOnProperty(name="audit.messaging.enabled",havingValue="true")
    static class Broker {
        @Bean Declarables auditTopology(){
            var exchange=new TopicExchange("invesctictrack.domain",true,false);var dead=new DirectExchange("audit.dead-letters",true,false);
            var queue=QueueBuilder.durable("audit.domain-events").deadLetterExchange("audit.dead-letters").deadLetterRoutingKey("invalid").build();var errors=QueueBuilder.durable("audit.failed-events").build();
            var all=new ArrayList<Declarable>();all.add(exchange);all.add(dead);all.add(queue);all.add(errors);all.add(BindingBuilder.bind(errors).to(dead).with("invalid"));
            for(String domain:Ingestion.PRODUCERS.keySet())all.add(BindingBuilder.bind(queue).to(exchange).with(domain+".*.*"));return new Declarables(all);
        }
        @Bean MessageRecoverer auditRecoverer(){return (message,cause)->{throw new AmqpRejectAndDontRequeueException("Audit ingestion retries exhausted",true,null);};}
        @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory){
            var factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connectionFactory);
            // Do not log failed Message objects or arbitrary producer payloads.
            factory.setErrorHandler(error->{throw new AmqpRejectAndDontRequeueException("Audit ingestion failed",true,null);});return factory;
        }
        @Bean EventReceiver eventReceiver(Ingestion ingestion){return new EventReceiver(ingestion);}
    }
    public static class EventReceiver {
        private final Ingestion ingestion;public EventReceiver(Ingestion ingestion){this.ingestion=ingestion;}
        @RabbitListener(queues="audit.domain-events")
        public void receive(Message message,Channel channel) throws IOException {
            long tag=message.getMessageProperties().getDeliveryTag();
            try {
                if(!"invesctictrack.domain".equals(message.getMessageProperties().getReceivedExchange()))throw new ApiException(400,"INVALID_EVENT_EXCHANGE","Unexpected event exchange.");
                ingestion.consume(message.getBody(),message.getMessageProperties().getReceivedRoutingKey());
                // The transactional proxy has committed both audit history and inbox before acknowledging.
                channel.basicAck(tag,false);
            }catch(ApiException e) {
                if(e.status>=500)throw e;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Audit event rejected: {}",e.code);channel.basicReject(tag,false);
            }
        }
    }
}
