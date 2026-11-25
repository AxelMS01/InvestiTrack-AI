package com.example.reportservice.infrastructure;

import com.example.reportservice.application.Projections;
import com.example.reportservice.api.ApiException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
    @Configuration
    @ConditionalOnProperty(name="report.messaging.enabled",havingValue="true")
    static class Broker {
        @Bean TopicExchange domainExchange(){return new TopicExchange("invesctictrack.domain",true,false);}
        @Bean DirectExchange deadLetters(){return new DirectExchange("reporting.dead-letters",true,false);}
        @Bean Queue reportingEvents(){return QueueBuilder.durable("reporting.domain-events").deadLetterExchange("reporting.dead-letters").deadLetterRoutingKey("invalid").build();}
        @Bean Queue reportingInvalidEvents(){return QueueBuilder.durable("reporting.invalid-events").build();}
        @Bean Binding projectBinding(Queue reportingEvents,TopicExchange domainExchange){return BindingBuilder.bind(reportingEvents).to(domainExchange).with("project.*.v1");}
        @Bean Binding proposalBinding(Queue reportingEvents,TopicExchange domainExchange){return BindingBuilder.bind(reportingEvents).to(domainExchange).with("proposal.*.v1");}
        @Bean Binding invalidBinding(Queue reportingInvalidEvents,DirectExchange deadLetters){return BindingBuilder.bind(reportingInvalidEvents).to(deadLetters).with("invalid");}
        @Bean Receiver receiver(Projections projections){return new Receiver(projections);}
    }
    static class Receiver {
        private final Projections projections;
        Receiver(Projections projections){this.projections=projections;}
        @RabbitListener(queues="reporting.domain-events")
        public void receive(Message message) {
            try{projections.accept(message.getBody());}
            catch(ApiException e){throw new AmqpRejectAndDontRequeueException("Invalid reporting event: "+e.code,e);}
        }
    }
}
