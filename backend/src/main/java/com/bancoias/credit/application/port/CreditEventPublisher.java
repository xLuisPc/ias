package com.bancoias.credit.application.port;

import com.bancoias.credit.domain.CreditApplication;
import reactor.core.publisher.Mono;

/**
 * Notifica a otros sistemas que una solicitud fue aprobada (punto opcional RabbitMQ).
 * Las implementaciones no deben propagar errores: la decision ya quedo persistida.
 */
public interface CreditEventPublisher {

	Mono<Void> publishApproved(CreditApplication application);
}
