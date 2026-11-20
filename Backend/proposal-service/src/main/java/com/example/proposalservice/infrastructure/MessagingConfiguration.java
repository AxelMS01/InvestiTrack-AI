package com.example.proposalservice.infrastructure;

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
    @ConditionalOnProperty(name="proposal.messaging.enabled",havingValue="true")
    static class Broker {
        @Bean TopicExchange domainExchange(){return new TopicExchange("invesctictrack.domain",true,false);}
        @Bean DirectExchange projectDeadLetters(){return new DirectExchange("proposal.dead-letters",true,false);}
        @Bean Queue projectEvents(){return QueueBuilder.durable("proposal.domain-events").deadLetterExchange("proposal.dead-letters").deadLetterRoutingKey("invalid").build();}
        @Bean Queue projectInvalidEvents(){return QueueBuilder.durable("proposal.invalid-events").build();}
        @Bean Binding proposalBinding(Queue projectEvents,TopicExchange domainExchange){return BindingBuilder.bind(projectEvents).to(domainExchange).with("project.ProjectCreated.v1");}
        @Bean Binding documentBinding(Queue projectEvents,TopicExchange domainExchange){return BindingBuilder.bind(projectEvents).to(domainExchange).with("document.DocumentDeleted.v1");}
        @Bean Binding invalidBinding(Queue projectInvalidEvents,DirectExchange projectDeadLetters){return BindingBuilder.bind(projectInvalidEvents).to(projectDeadLetters).with("invalid");}
        @Bean EventReceiver eventReceiver(ProposalInbox inbox){return new EventReceiver(inbox);}
    }
    static class EventReceiver {
        private final ProposalInbox inbox;
        EventReceiver(ProposalInbox inbox){this.inbox=inbox;}
        @RabbitListener(queues="proposal.domain-events")
        public void receive(Message message) {
            try{inbox.accept(message.getBody());}
            catch(com.example.proposalservice.api.ApiException e){throw new AmqpRejectAndDontRequeueException("Event rejected: "+e.code(),e);}
        }
    }
}
