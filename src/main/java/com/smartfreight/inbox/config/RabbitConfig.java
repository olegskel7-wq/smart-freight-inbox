package com.smartfreight.inbox.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology and consumer tuning for the async AI pipeline (Phase 1.3).
 *
 * Topology:
 * <pre>
 *   ticket.events (topic) --[ticket.#]--> q.ticket.process --(reject after 3 attempts)--> ticket.events.dlx (fanout) --> q.ticket.process.dlq
 * </pre>
 *
 * All declarables are durable — they survive a broker restart, matching the
 * durability of the outbox/ticket rows in PostgreSQL.
 *
 * Backpressure: the real bottleneck is Ollama. docker-compose runs it with
 * OLLAMA_NUM_PARALLEL=2, so the listener runs 2 consumers with prefetch=1 each —
 * at most 2 AI pipelines in flight, no matter how many emails arrive at once.
 * Excess load waits in the durable queue instead of stampeding the GPU.
 */
@Configuration
public class RabbitConfig {

    public static final String TICKET_EXCHANGE = "ticket.events";
    public static final String TICKET_DLX = "ticket.events.dlx";
    public static final String PROCESS_QUEUE = "q.ticket.process";
    public static final String PROCESS_DLQ = "q.ticket.process.dlq";

    /** Number of concurrent pipeline consumers. Keep in sync with OLLAMA_NUM_PARALLEL. */
    @Value("${ai-pipeline.consumer-concurrency:2}")
    private int consumerConcurrency;

    @Bean
    public TopicExchange ticketExchange() {
        return new TopicExchange(TICKET_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange ticketDeadLetterExchange() {
        return new FanoutExchange(TICKET_DLX, true, false);
    }

    @Bean
    public Queue ticketProcessQueue() {
        return QueueBuilder.durable(PROCESS_QUEUE)
                .withArgument("x-dead-letter-exchange", TICKET_DLX)
                .build();
    }

    @Bean
    public Queue ticketProcessDlq() {
        return QueueBuilder.durable(PROCESS_DLQ).build();
    }

    @Bean
    public Binding ticketProcessBinding() {
        // "ticket.#" catches every ticket event type (created, retry-requested, future ones).
        return BindingBuilder.bind(ticketProcessQueue()).to(ticketExchange()).with("ticket.#");
    }

    @Bean
    public Binding ticketDlqBinding() {
        return BindingBuilder.bind(ticketProcessDlq()).to(ticketDeadLetterExchange());
    }

    /**
     * Listener factory for the AI pipeline consumer.
     *
     * Poison-message handling: a message that keeps throwing (e.g. unparseable payload)
     * is retried 3 times in-memory with backoff, then rejected WITHOUT requeue —
     * the x-dead-letter-exchange argument on the queue routes it to the DLQ. Without
     * {@link RejectAndDontRequeueRecoverer} the default would be infinite redelivery.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory aiPipelineListenerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(consumerConcurrency);
        factory.setMaxConcurrentConsumers(consumerConcurrency);
        // prefetch=1: a consumer takes the next message only when fully done with the
        // previous one — fair dispatch for jobs that run tens of seconds each.
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                // 1 delivery + 2 retries = 3 attempts total, with exponential backoff
                .maxRetries(2)
                .backOffOptions(1_000, 2.0, 10_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
