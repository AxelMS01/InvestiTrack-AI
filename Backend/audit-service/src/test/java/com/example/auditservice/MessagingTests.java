package com.example.auditservice;

import com.example.auditservice.api.ApiException;
import com.example.auditservice.application.Ingestion;
import com.example.auditservice.infrastructure.MessagingConfiguration.EventReceiver;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagingTests {
    Message message(){var properties=new MessageProperties();properties.setDeliveryTag(1);properties.setReceivedExchange("invesctictrack.domain");properties.setReceivedRoutingKey("project.ProjectChanged.v1");return new Message("{}".getBytes(),properties);}
    @Test void acknowledgesOnlyAfterTransactionalIngestionAndImmediatelyRejectsInvalidEvents() throws Exception {
        var ingestion=mock(Ingestion.class);var channel=mock(Channel.class);var receiver=new EventReceiver(ingestion);var message=message();
        receiver.receive(message,channel);var order=inOrder(ingestion,channel);order.verify(ingestion).consume(any(),eq("project.ProjectChanged.v1"));order.verify(channel).basicAck(1,false);
        reset(channel);when(ingestion.consume(any(),anyString())).thenThrow(new ApiException(400,"INVALID_EVENT","Invalid event"));receiver.receive(message,channel);verify(channel).basicReject(1,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void transientFailuresAreNotAcknowledgedAndUnexpectedExchangesAreRejected() throws Exception {
        var ingestion=mock(Ingestion.class);var channel=mock(Channel.class);var receiver=new EventReceiver(ingestion);var message=message();
        when(ingestion.consume(any(),anyString())).thenThrow(ApiException.unavailable());assertThatThrownBy(()->receiver.receive(message,channel)).isInstanceOf(ApiException.class);verifyNoInteractions(channel);
        message.getMessageProperties().setReceivedExchange("");receiver.receive(message,channel);verify(channel).basicReject(1,false);
    }
}
