package com.example.documentservice.infrastructure;

import com.example.documentservice.application.Documents;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
    @Bean @ConditionalOnProperty(name="document.messaging.enabled",havingValue="true")
    TopicExchange domainExchange(){return new TopicExchange("invesctictrack.domain",true,false);}
    @Bean @ConditionalOnProperty(name="document.jobs.enabled",havingValue="true")
    UploadExpiry uploadExpiry(Documents documents){return new UploadExpiry(documents);}
    static class UploadExpiry {
        private final Documents documents;UploadExpiry(Documents documents){this.documents=documents;}
        @Scheduled(fixedDelayString="${document.jobs.poll-ms:60000}") public void expire(){documents.expireUploads();}
    }
}
