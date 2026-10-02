package com.bancoias.credit.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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

/**
 * Topologia: exchange topic "credit.events" -> cola durable "credit.application.approved.audit".
 * Un exchange topic permite que otros sistemas creen sus propias colas sin tocar este servicio.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMessagingConfig {

	@Bean
	public TopicExchange creditEventsExchange(@Value("${app.rabbitmq.exchange}") String exchange) {
		return new TopicExchange(exchange, true, false);
	}

	@Bean
	public Queue approvedAuditQueue(@Value("${app.rabbitmq.approved-queue}") String queue) {
		return QueueBuilder.durable(queue).build();
	}

	@Bean
	public Binding approvedAuditBinding(Queue approvedAuditQueue, TopicExchange creditEventsExchange,
			@Value("${app.rabbitmq.approved-routing-key}") String routingKey) {
		return BindingBuilder.bind(approvedAuditQueue).to(creditEventsExchange).with(routingKey);
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
	public ApprovedApplicationListener approvedApplicationListener() {
		return new ApprovedApplicationListener();
	}
}
