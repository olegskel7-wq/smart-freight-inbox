package com.smartfreight.inbox.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.smartfreight.inbox.config.RabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DlqAdminService.
 *
 * The RabbitTemplate is stubbed to hand the callback a mocked Channel, so the
 * tests drive the real get → publish → ack loop without a broker.
 */
@ExtendWith(MockitoExtension.class)
class DlqAdminServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private Channel channel;

    private DlqAdminService service;

    @BeforeEach
    void setUp() {
        service = new DlqAdminService(rabbitTemplate, amqpAdmin);
    }

    private void runCallbackAgainstChannel() {
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
    }

    private GetResponse dlqMessage(long deliveryTag, String body) {
        return new GetResponse(
                new Envelope(deliveryTag, false, RabbitConfig.TICKET_DLX, ""),
                new AMQP.BasicProperties.Builder().contentType("text/plain").build(),
                body.getBytes(StandardCharsets.UTF_8),
                0);
    }

    @Test
    void messageCount_returnsQueueDepth() {
        when(amqpAdmin.getQueueInfo(RabbitConfig.PROCESS_DLQ))
                .thenReturn(new QueueInformation(RabbitConfig.PROCESS_DLQ, 6, 0));

        assertThat(service.messageCount()).isEqualTo(6);
    }

    @Test
    void messageCount_queueMissing_returnsZero() {
        when(amqpAdmin.getQueueInfo(RabbitConfig.PROCESS_DLQ)).thenReturn(null);

        assertThat(service.messageCount()).isZero();
    }

    @Test
    void requeue_movesEachMessageToWorkQueueAndAcks() throws Exception {
        runCallbackAgainstChannel();
        GetResponse first = dlqMessage(11L, "{\"ticketId\":1}");
        GetResponse second = dlqMessage(12L, "{\"ticketId\":2}");
        when(channel.basicGet(RabbitConfig.PROCESS_DLQ, false))
                .thenReturn(first, second, null);

        int moved = service.requeue(100);

        assertThat(moved).isEqualTo(2);
        verify(channel).basicPublish(eq(""), eq(RabbitConfig.PROCESS_QUEUE),
                eq(first.getProps()), eq(first.getBody()));
        verify(channel).basicPublish(eq(""), eq(RabbitConfig.PROCESS_QUEUE),
                eq(second.getProps()), eq(second.getBody()));
        verify(channel).basicAck(11L, false);
        verify(channel).basicAck(12L, false);
    }

    @Test
    void requeue_publishesBeforeAcking_crashLosesNothing() throws Exception {
        runCallbackAgainstChannel();
        GetResponse message = dlqMessage(21L, "{\"ticketId\":3}");
        when(channel.basicGet(RabbitConfig.PROCESS_DLQ, false)).thenReturn(message, (GetResponse) null);

        var inOrder = org.mockito.Mockito.inOrder(channel);

        service.requeue(100);

        inOrder.verify(channel).basicPublish(any(), any(), any(), any());
        inOrder.verify(channel).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void requeue_respectsLimit() throws Exception {
        runCallbackAgainstChannel();
        when(channel.basicGet(RabbitConfig.PROCESS_DLQ, false))
                .thenAnswer(inv -> dlqMessage(31L, "{\"ticketId\":4}"));

        int moved = service.requeue(3);

        assertThat(moved).isEqualTo(3);
        verify(channel, org.mockito.Mockito.times(3)).basicPublish(any(), any(), any(), any());
    }

    @Test
    void requeue_emptyDlq_doesNothing() throws Exception {
        runCallbackAgainstChannel();
        when(channel.basicGet(RabbitConfig.PROCESS_DLQ, false)).thenReturn(null);

        int moved = service.requeue(100);

        assertThat(moved).isZero();
        verify(channel, never()).basicPublish(any(), any(), any(), any());
    }

    @Test
    void purge_delegatesToAmqpAdmin() {
        when(amqpAdmin.purgeQueue(RabbitConfig.PROCESS_DLQ)).thenReturn(5);

        assertThat(service.purge()).isEqualTo(5);
    }
}
