package com.bancoias.credit.infrastructure.messaging;

import java.time.Clock;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Topologia:
 * exchange topic "credit.events" -> cola durable "credit.application.approved.audit"
 * y, para lo que falla tras los reintentos, exchange "credit.events.dlx" -> "credit.application.approved.audit.dlq".
 * Un exchange topic permite que otros sistemas creen sus propias colas sin tocar este servicio.
 * Cada cola envia sus rechazos al DLX con su propio nombre como routing key, asi cada consumidor tiene su DLQ.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMessagingConfig {

	@Bean
	public TopicExchange creditEventsExchange(@Value("${app.rabbitmq.exchange}") String exchange) {
		return new TopicExchange(exchange, true, false);
	}

	@Bean
	public Queue approvedAuditQueue(@Value("${app.rabbitmq.approved-queue}") String queue,
			@Value("${app.rabbitmq.dead-letter-exchange}") String deadLetterExchange) {
		return QueueBuilder.durable(queue)
				.deadLetterExchange(deadLetterExchange)
				.deadLetterRoutingKey(queue)
				.build();
	}

	@Bean
	public Binding approvedAuditBinding(Queue approvedAuditQueue, TopicExchange creditEventsExchange,
			@Value("${app.rabbitmq.approved-routing-key}") String routingKey) {
		return BindingBuilder.bind(approvedAuditQueue).to(creditEventsExchange).with(routingKey);
	}

	@Bean
	public DirectExchange deadLetterExchange(@Value("${app.rabbitmq.dead-letter-exchange}") String exchange) {
		return new DirectExchange(exchange, true, false);
	}

	@Bean
	public Queue approvedAuditDeadLetterQueue(@Value("${app.rabbitmq.approved-dead-letter-queue}") String queue) {
		return QueueBuilder.durable(queue).build();
	}

	@Bean
	public Binding approvedAuditDeadLetterBinding(Queue approvedAuditDeadLetterQueue,
			DirectExchange deadLetterExchange, @Value("${app.rabbitmq.approved-queue}") String sourceQueue) {
		return BindingBuilder.bind(approvedAuditDeadLetterQueue).to(deadLetterExchange).with(sourceQueue);
	}

	@Bean
	public MessageConverter jsonMessageConverter() {
		return new JacksonJsonMessageConverter();
	}

	@Bean
	public RabbitCreditEventPublisher rabbitCreditEventPublisher(RabbitTemplate rabbitTemplate,
			@Value("${app.rabbitmq.exchange}") String exchange,
			@Value("${app.rabbitmq.approved-routing-key}") String routingKey) {
		return new RabbitCreditEventPublisher(rabbitTemplate, exchange, routingKey);
	}

	@Bean
	public ApprovedApplicationAuditRepository approvedApplicationAuditRepository(DatabaseClient db, Clock clock) {
		return new ApprovedApplicationAuditRepository(db, clock);
	}

	@Bean
	public ApprovedApplicationListener approvedApplicationListener(ApprovedApplicationAuditRepository audit) {
		return new ApprovedApplicationListener(audit);
	}
}
