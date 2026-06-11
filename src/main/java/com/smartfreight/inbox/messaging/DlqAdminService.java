package com.smartfreight.inbox.messaging;

import com.rabbitmq.client.GetResponse;
import com.smartfreight.inbox.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator tooling for the pipeline dead-letter queue (Phase 1.3, session 2).
 *
 * Messages land in {@link RabbitConfig#PROCESS_DLQ} only after the consumer's retry
 * advice gave up (3 attempts) — i.e. poison payloads or a bug. They need a human
 * decision: requeue after a fix is deployed, or purge if they are garbage.
 *
 * Requeue mechanics: basicGet (manual ack) → republish to the work queue via the
 * default exchange → ack the DLQ copy. Ack-after-publish means a crash mid-requeue
 * duplicates a message rather than losing it — the same at-least-once stance as the
 * rest of the pipeline, absorbed by the idempotent consumer. A message that is
 * still poison simply travels back to the DLQ after another 3 attempts.
 */
@Service
public class DlqAdminService {

    private static final Logger log = LoggerFactory.getLogger(DlqAdminService.class);

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    public DlqAdminService(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    /** @return current DLQ depth, or 0 if the queue does not exist (yet) */
    public long messageCount() {
        QueueInformation info = amqpAdmin.getQueueInfo(RabbitConfig.PROCESS_DLQ);
        return info != null ? info.getMessageCount() : 0;
    }

    /**
     * Moves up to {@code maxMessages} messages from the DLQ back onto the work queue.
     *
     * Publishes to the queue directly (default exchange, routing key = queue name)
     * instead of the topic exchange: requeue must be deterministic, regardless of
     * what bindings exist by then.
     *
     * @param maxMessages safety cap per invocation
     * @return number of messages actually moved
     */
    public int requeue(int maxMessages) {
        Integer moved = rabbitTemplate.execute(channel -> {
            int count = 0;
            while (count < maxMessages) {
                GetResponse response = channel.basicGet(RabbitConfig.PROCESS_DLQ, false);
                if (response == null) {
                    break;
                }
                channel.basicPublish("", RabbitConfig.PROCESS_QUEUE,
                        response.getProps(), response.getBody());
                channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                count++;
            }
            return count;
        });
        int result = moved != null ? moved : 0;
        if (result > 0) {
            log.info("DLQ requeue: moved {} message(s) back to {}", result, RabbitConfig.PROCESS_QUEUE);
        }
        return result;
    }

    /**
     * Irreversibly drops every message in the DLQ. For payloads confirmed to be
     * garbage (e.g. produced by a since-fixed serialization bug).
     *
     * @return number of messages purged
     */
    public int purge() {
        int purged = amqpAdmin.purgeQueue(RabbitConfig.PROCESS_DLQ);
        if (purged > 0) {
            log.warn("DLQ purge: dropped {} message(s)", purged);
        }
        return purged;
    }
}
