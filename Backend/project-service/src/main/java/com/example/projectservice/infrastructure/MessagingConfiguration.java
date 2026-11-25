package com.example.projectservice.infrastructure;

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
    @ConditionalOnProperty(name="project.messaging.enabled",havingValue="true")
    static class Broker {
        @Bean TopicExchange domainExchange(){return new TopicExchange("invesctictrack.domain",true,false);}
        @Bean DirectExchange projectDeadLetters(){return new DirectExchange("project.dead-letters",true,false);}
        @Bean Queue projectEvents(){return QueueBuilder.durable("project.domain-events").deadLetterExchange("project.dead-letters").deadLetterRoutingKey("invalid").build();}
        @Bean Queue projectInvalidEvents(){return QueueBuilder.durable("project.invalid-events").build();}
        @Bean Binding proposalBinding(Queue projectEvents,TopicExchange domainExchange){return BindingBuilder.bind(projectEvents).to(domainExchange).with("proposal.ProposalApproved.v1");}
        @Bean Binding documentBinding(Queue projectEvents,TopicExchange domainExchange){return BindingBuilder.bind(projectEvents).to(domainExchange).with("document.DocumentDeleted.v1");}
        @Bean Binding invalidBinding(Queue projectInvalidEvents,DirectExchange projectDeadLetters){return BindingBuilder.bind(projectInvalidEvents).to(projectDeadLetters).with("invalid");}
        @Bean EventReceiver eventReceiver(ApprovalInbox inbox){return new EventReceiver(inbox);}
    }
    static class EventReceiver {
        private final ApprovalInbox inbox;
        EventReceiver(ApprovalInbox inbox){this.inbox=inbox;}
        @RabbitListener(queues="project.domain-events")
        public void receive(Message message) {
            try{inbox.accept(message.getBody());}
            catch(com.example.projectservice.api.ApiException e){throw new AmqpRejectAndDontRequeueException("Event rejected: "+e.code,e);}
        }
    }
}
