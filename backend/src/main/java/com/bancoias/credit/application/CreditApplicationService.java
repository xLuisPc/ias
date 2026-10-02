package com.bancoias.credit.application;

import com.bancoias.credit.application.port.CreditApplicationRepository;
import com.bancoias.credit.application.port.CreditEventPublisher;
import com.bancoias.credit.application.port.CustomerRepository;
import com.bancoias.credit.domain.CreditApplication;
import com.bancoias.credit.domain.CreditPolicy;
import com.bancoias.credit.domain.CreditRequest;
import com.bancoias.credit.domain.Customer;
import com.bancoias.credit.domain.RejectionReason;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Caso de uso principal: registrar, evaluar, persistir y consultar solicitudes de credito.
 */
@Service
public class CreditApplicationService {

	public static final int MAX_RECENT_LIMIT = 100;

	private final CreditApplicationRepository applications;
	private final CustomerRepository customers;
	private final CreditEventPublisher eventPublisher;
	private final TransactionalOperator transactionalOperator;
	private final Clock clock;
	private final CreditPolicy policy = new CreditPolicy();

	public CreditApplicationService(CreditApplicationRepository applications, CustomerRepository customers,
			CreditEventPublisher eventPublisher, TransactionalOperator transactionalOperator, Clock clock) {
		this.applications = applications;
		this.customers = customers;
		this.eventPublisher = eventPublisher;
		this.transactionalOperator = transactionalOperator;
		this.clock = clock;
	}

	/**
	 * Flujo:
	 * 1. Si la referencia ya existe -> se devuelve el resultado original (o conflicto si los datos difieren).
	 * 2. Si no existe -> se evalua y persiste en una unica transaccion (reserva de cupo + insercion).
	 * 3. Si otra peticion con la misma referencia gano la carrera, la restriccion UNIQUE hace fallar
	 *    la insercion, la transaccion se revierte (incluida la reserva de cupo) y se aplica el paso 1.
	 * 4. Si fue aprobada, se publica el evento despues del commit.
	 */
	public Mono<ProcessingResult> process(CreditRequest request) {
		return applications.findByReference(request.applicationReference())
				.flatMap(existing -> replayOrConflict(existing, request))
				.switchIfEmpty(Mono.defer(() -> evaluateAndPersist(request)
						.flatMap(this::publishIfApproved)
						.map(ProcessingResult::created)
						.onErrorResume(DuplicateApplicationReferenceException.class,
								e -> applications.findByReference(request.applicationReference())
										.flatMap(existing -> replayOrConflict(existing, request)))));
	}

	public Mono<CreditApplication> findByReference(String applicationReference) {
		return applications.findByReference(applicationReference)
				.switchIfEmpty(Mono.error(() -> new ApplicationNotFoundException(applicationReference)));
	}

	public Flux<CreditApplication> findRecent(int limit) {
		return applications.findRecent(Math.clamp(limit, 1, MAX_RECENT_LIMIT));
	}

	public Flux<Customer> findCustomers() {
		return customers.findAll();
	}

	private Mono<CreditApplication> evaluateAndPersist(CreditRequest request) {
		Mono<CreditApplication> decision = customers.findById(request.customerId())
				.map(Optional::of)
				.defaultIfEmpty(Optional.empty())
				.flatMap(customer -> policy.evaluate(request, customer)
						.map(reason -> Mono.just(CreditApplication.rejected(request, reason, now())))
						.orElseGet(() -> reserveCredit(request)))
				.flatMap(applications::save);

		return transactionalOperator.transactional(decision);
	}

	private Mono<CreditApplication> reserveCredit(CreditRequest request) {
		return customers.tryReserveCredit(request.customerId(), request.amount())
				.map(reserved -> reserved
						? CreditApplication.approved(request, now())
						: CreditApplication.rejected(request, RejectionReason.CREDIT_LIMIT_EXCEEDED, now()));
	}

	private Mono<CreditApplication> publishIfApproved(CreditApplication application) {
		if (!application.isApproved()) {
			return Mono.just(application);
		}
		return eventPublisher.publishApproved(application).thenReturn(application);
	}

	private Mono<ProcessingResult> replayOrConflict(CreditApplication existing, CreditRequest request) {
		if (existing.hasSameContentAs(request)) {
			return Mono.just(ProcessingResult.replayed(existing));
		}
		return Mono.error(new ApplicationReferenceConflictException(existing));
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}
}
