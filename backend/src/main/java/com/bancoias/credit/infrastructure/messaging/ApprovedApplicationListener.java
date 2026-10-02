package com.bancoias.credit.infrastructure.messaging;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * Consumidor de ejemplo que simula a "otro sistema" (auditoria) reaccionando a la aprobacion.
 * RabbitMQ entrega at-least-once, asi que un mismo mensaje puede llegar mas de una vez: se registra una sola vez.
 * Si el registro falla, Spring AMQP reintenta con backoff; al agotar los intentos rechaza el mensaje sin
 * reencolarlo y RabbitMQ lo mueve a la dead-letter queue. Un mensaje sin messageId no cumple el contrato
 * y sigue el mismo camino.
 */
public class ApprovedApplicationListener {

	private static final Logger log = LoggerFactory.getLogger(ApprovedApplicationListener.class);
	private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(5);

	private final ApprovedApplicationAuditRepository audit;

	public ApprovedApplicationListener(ApprovedApplicationAuditRepository audit) {
		this.audit = audit;
	}

	@RabbitListener(queues = "${app.rabbitmq.approved-queue}")
	public void onApproved(CreditApplicationApprovedEvent event, @Header(AmqpHeaders.MESSAGE_ID) String messageId) {
		// El listener corre en un hilo del contenedor de Spring AMQP, no en el event loop: aqui se puede bloquear.
		boolean recorded = Boolean.TRUE.equals(audit.recordOnce(messageId, event).block(RECORD_TIMEOUT));
		if (recorded) {
			log.info("[consumidor] Aprobacion registrada en auditoria: ref={}, cliente={}, monto={}",
					event.applicationReference(), event.customerId(), event.amount());
		} else {
			log.info("[consumidor] Mensaje duplicado ignorado: messageId={}", messageId);
		}
	}
}
