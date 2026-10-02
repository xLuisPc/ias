package com.bancoias.credit.infrastructure.messaging;

import com.bancoias.credit.application.port.CreditEventPublisher;
import com.bancoias.credit.domain.CreditApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Implementacion por defecto cuando RabbitMQ esta deshabilitado: no publica nada. */
@Component
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCreditEventPublisher implements CreditEventPublisher {

	@Override
	public Mono<Void> publishApproved(CreditApplication application) {
		return Mono.empty();
	}
}
