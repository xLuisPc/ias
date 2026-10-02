package com.bancoias.credit.infrastructure.messaging;

import com.bancoias.credit.application.port.CreditEventPublisher;
import com.bancoias.credit.domain.CreditApplication;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Publica el evento de aprobacion en RabbitMQ.
 * RabbitTemplate es bloqueante, por eso se ejecuta en boundedElastic para no bloquear el event loop.
 * Solo se considera publicado cuando el broker lo confirma (publisher confirm). Las fallas momentaneas se
 * reintentan; el timeout acota el total para no demorar la respuesta.
 * Si aun asi falla se registra el error y no se afecta la respuesta: la decision ya fue persistida.
 */
public class RabbitCreditEventPublisher implements CreditEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(RabbitCreditEventPublisher.class);
	private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(1);
	private static final int PUBLISH_RETRIES = 2;
	private static final Duration RETRY_BACKOFF = Duration.ofMillis(100);

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
		return Mono.fromRunnable(() -> sendAndAwaitConfirm(event))
				.subscribeOn(Schedulers.boundedElastic())
				.retryWhen(Retry.backoff(PUBLISH_RETRIES, RETRY_BACKOFF))
				.timeout(PUBLISH_TIMEOUT)
				.doOnSuccess(ignored -> log.info("Evento {} publicado para {}", event.eventType(),
						event.applicationReference()))
				.onErrorResume(e -> {
					log.error("No se pudo publicar el evento de aprobacion para {}", application.applicationReference(), e);
					return Mono.empty();
				})
				.then();
	}

	private void sendAndAwaitConfirm(CreditApplicationApprovedEvent event) {
		rabbitTemplate.invoke(operations -> {
			operations.convertAndSend(exchange, routingKey, event, message -> {
				// messageId = referencia: permite a los consumidores descartar duplicados.
				message.getMessageProperties().setMessageId(event.applicationReference());
				return message;
			});
			// Lanza excepcion si el broker rechaza el mensaje (nack) o no confirma a tiempo.
			operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
			return null;
		});
	}
}
