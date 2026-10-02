package com.bancoias.credit.infrastructure.messaging;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * Registro de auditoria del consumidor de ejemplo.
 * La PK sobre message_id lo hace idempotente, con la misma estrategia que RF05: la base de datos
 * rechaza el duplicado aunque dos entregas del mismo mensaje lleguen a la vez.
 */
public class ApprovedApplicationAuditRepository {

	private final DatabaseClient db;
	private final Clock clock;

	public ApprovedApplicationAuditRepository(DatabaseClient db, Clock clock) {
		this.db = db;
		this.clock = clock;
	}

	/** Devuelve true si registro la aprobacion y false si el mensaje ya habia sido procesado. */
	public Mono<Boolean> recordOnce(String messageId, CreditApplicationApprovedEvent event) {
		return db.sql("""
						INSERT INTO approved_application_audit
						    (message_id, application_reference, customer_id, amount, approved_at, received_at)
						VALUES (:messageId, :reference, :customerId, :amount, :approvedAt, :receivedAt)""")
				.bind("messageId", messageId)
				.bind("reference", event.applicationReference())
				.bind("customerId", event.customerId())
				.bind("amount", event.amount())
				.bind("approvedAt", event.approvedAt())
				.bind("receivedAt", OffsetDateTime.now(clock))
				.fetch().rowsUpdated()
				.thenReturn(true)
				// Solo la violacion de la PK significa "duplicado". Cualquier otro error se propaga y se reintenta.
				.onErrorReturn(DuplicateKeyException.class, false);
	}
}
