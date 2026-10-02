package com.bancoias.credit.infrastructure.messaging;

import com.bancoias.credit.application.port.CreditEventPublisher;
import com.bancoias.credit.domain.CreditApplication;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publica el evento de aprobacion en RabbitMQ.
 * RabbitTemplate es bloqueante, por eso se ejecuta en boundedElastic para no bloquear el event loop.
 * Si la publicacion falla se registra el error y no se afecta la respuesta: la decision ya fue persistida.
 */
public class RabbitCreditEventPublisher implements CreditEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(RabbitCreditEventPublisher.class);
	private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(3);

	private final RabbitTemplate rabbitTemplate;
	private final String exchange;
	private final String routingKey;

	public RabbitCreditEventPublisher(RabbitTemplate rabbitTemplate, String exchange, String routingKey) {
		this.rabbitTemplate = rabbitTemplate;
		this.exchange = exchange;
		this.routingKey = routingKey;
	}

	@Override
	public Mono<Void> publishApproved(CreditApplication application) {
		var event = CreditApplicationApprovedEvent.from(application);
		return Mono.fromRunnable(() -> rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
					// messageId = referencia: permite a los consumidores descartar duplicados.
					message.getMessageProperties().setMessageId(application.applicationReference());
					return message;
				}))
				.subscribeOn(Schedulers.boundedElastic())
				.timeout(PUBLISH_TIMEOUT)
				.doOnSuccess(ignored -> log.info("Evento {} publicado para {}", event.eventType(),
						event.applicationReference()))
				.onErrorResume(e -> {
					log.error("No se pudo publicar el evento de aprobacion para {}", application.applicationReference(), e);
					return Mono.empty();
				})
				.then();
	}
}
