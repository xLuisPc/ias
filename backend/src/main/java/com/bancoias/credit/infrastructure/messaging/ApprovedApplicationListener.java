package com.bancoias.credit.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Consumidor de ejemplo que simula a "otro sistema" reaccionando a la aprobacion
 * (p. ej. auditoria o notificaciones). Solo registra el evento recibido.
 */
public class ApprovedApplicationListener {

	private static final Logger log = LoggerFactory.getLogger(ApprovedApplicationListener.class);

	@RabbitListener(queues = "${app.rabbitmq.approved-queue}")
	public void onApproved(CreditApplicationApprovedEvent event) {
		log.info("[consumidor] Solicitud aprobada recibida: ref={}, cliente={}, monto={}",
				event.applicationReference(), event.customerId(), event.amount());
	}
}
