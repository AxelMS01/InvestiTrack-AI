package com.example.auditservice.infrastructure;

import com.example.auditservice.application.Ingestion;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrokerConfigurationTests {
    @Test void brokerFactoryConfiguresManualAcknowledgmentsAndRetryWithoutOpeningConnections(){
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(MessagingConfiguration.class).withBean(Ingestion.class,()->mock(Ingestion.class))
            .withPropertyValues("audit.messaging.enabled=true","spring.rabbitmq.dynamic=false","spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.simple.acknowledge-mode=manual","spring.rabbitmq.listener.simple.retry.enabled=true","spring.rabbitmq.listener.simple.retry.max-retries=5")
            .run(context->{assertThat(context).hasNotFailed();var factory=context.getBean(SimpleRabbitListenerContainerFactory.class);var container=factory.createListenerContainer();assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);});
    }
}
