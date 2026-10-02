package com.bancoias.credit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bancoias.credit.application.port.CreditApplicationRepository;
import com.bancoias.credit.application.port.CreditEventPublisher;
import com.bancoias.credit.application.port.CustomerRepository;
import com.bancoias.credit.domain.ApplicationStatus;
import com.bancoias.credit.domain.CreditApplication;
import com.bancoias.credit.domain.CreditRequest;
import com.bancoias.credit.domain.Customer;
import com.bancoias.credit.domain.CustomerStatus;
import com.bancoias.credit.domain.RejectionReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Pruebas del caso de uso aislando la infraestructura (repositorios, transaccion y mensajeria simulados).
 */
@ExtendWith(MockitoExtension.class)
class CreditApplicationServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final CreditRequest REQUEST =
			new CreditRequest("REF-001", "CLI-1001", new BigDecimal("6000000"), 24);
	private static final Customer ELIGIBLE =
			new Customer("CLI-1001", CustomerStatus.ELIGIBLE, new BigDecimal("10000000"), BigDecimal.ZERO);

	@Mock
	private CreditApplicationRepository applications;
	@Mock
	private CustomerRepository customers;
	@Mock
	private CreditEventPublisher eventPublisher;
	@Mock
	private TransactionalOperator transactionalOperator;

	private CreditApplicationService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		lenient().when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
		lenient().when(applications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
		lenient().when(eventPublisher.publishApproved(any())).thenReturn(Mono.empty());
		service = new CreditApplicationService(applications, customers, eventPublisher, transactionalOperator,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void approvesReservesCreditAndPublishesEvent() {
		when(applications.findByReference("REF-001")).thenReturn(Mono.empty());
		when(customers.findById("CLI-1001")).thenReturn(Mono.just(ELIGIBLE));
		when(customers.tryReserveCredit("CLI-1001", REQUEST.amount())).thenReturn(Mono.just(true));

		StepVerifier.create(service.process(REQUEST))
				.assertNext(result -> {
					assertThat(result.replayed()).isFalse();
					assertThat(result.application().status()).isEqualTo(ApplicationStatus.APPROVED);
					assertThat(result.application().processedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
				})
				.verifyComplete();

		verify(eventPublisher).publishApproved(any());
	}

	@Test
	void rejectsWithoutReservingCreditWhenBusinessRulesFail() {
		var blocked = new Customer("CLI-1001", CustomerStatus.BLOCKED, new BigDecimal("10000000"), BigDecimal.ZERO);
		when(applications.findByReference("REF-001")).thenReturn(Mono.empty());
		when(customers.findById("CLI-1001")).thenReturn(Mono.just(blocked));

		StepVerifier.create(service.process(REQUEST))
				.assertNext(result -> {
					assertThat(result.application().status()).isEqualTo(ApplicationStatus.REJECTED);
					assertThat(result.application().rejectionReason()).isEqualTo(RejectionReason.CUSTOMER_NOT_ELIGIBLE);
				})
				.verifyComplete();

		verify(customers, never()).tryReserveCredit(any(), any());
		verify(applications).save(any());
		verify(eventPublisher, never()).publishApproved(any());
	}

	@Test
	void rejectsWhenAtomicReservationFailsEvenIfSnapshotHadCredit() {
		// Simula que otra solicitud simultanea consumio el cupo entre la lectura y la reserva.
		when(applications.findByReference("REF-001")).thenReturn(Mono.empty());
		when(customers.findById("CLI-1001")).thenReturn(Mono.just(ELIGIBLE));
		when(customers.tryReserveCredit("CLI-1001", REQUEST.amount())).thenReturn(Mono.just(false));

		StepVerifier.create(service.process(REQUEST))
				.assertNext(result -> assertThat(result.application().rejectionReason())
						.isEqualTo(RejectionReason.CREDIT_LIMIT_EXCEEDED))
				.verifyComplete();

		verify(eventPublisher, never()).publishApproved(any());
	}

	@Test
	void returnsOriginalResultWhenReferenceIsRepeatedWithSameData() {
		var original = CreditApplication.approved(REQUEST, OffsetDateTime.parse("2026-01-01T08:00:00Z"));
		when(applications.findByReference("REF-001")).thenReturn(Mono.just(original));

		var sameDataDifferentScale = new CreditRequest("REF-001", "CLI-1001", new BigDecimal("6000000.00"), 24);

		StepVerifier.create(service.process(sameDataDifferentScale))
				.assertNext(result -> {
					assertThat(result.replayed()).isTrue();
					assertThat(result.application()).isEqualTo(original);
				})
				.verifyComplete();

		verify(customers, never()).tryReserveCredit(any(), any());
		verify(applications, never()).save(any());
	}

	@Test
	void failsWithConflictWhenReferenceIsRepeatedWithDifferentData() {
		var original = CreditApplication.approved(REQUEST, OffsetDateTime.parse("2026-01-01T08:00:00Z"));
		when(applications.findByReference("REF-001")).thenReturn(Mono.just(original));

		var differentAmount = new CreditRequest("REF-001", "CLI-1001", new BigDecimal("7000000"), 24);

		StepVerifier.create(service.process(differentAmount))
				.expectErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ApplicationReferenceConflictException.class)
						.extracting(e -> ((ApplicationReferenceConflictException) e).original())
						.isEqualTo(original))
				.verify();

		verify(applications, never()).save(any());
	}

	@Test
	void resolvesRaceOnSameReferenceUsingTheStoredApplication() {
		// Dos peticiones con la misma referencia: esta pierde la carrera en el UNIQUE de la base de datos.
		var winner = CreditApplication.approved(REQUEST, OffsetDateTime.parse("2026-01-15T09:59:59Z"));
		when(applications.findByReference("REF-001")).thenReturn(Mono.empty(), Mono.just(winner));
		when(customers.findById("CLI-1001")).thenReturn(Mono.just(ELIGIBLE));
		when(customers.tryReserveCredit("CLI-1001", REQUEST.amount())).thenReturn(Mono.just(true));
		doReturn(Mono.error(new DuplicateApplicationReferenceException("REF-001", null)))
				.when(applications).save(any());

		StepVerifier.create(service.process(REQUEST))
				.assertNext(result -> {
					assertThat(result.replayed()).isTrue();
					assertThat(result.application()).isEqualTo(winner);
				})
				.verifyComplete();

		verify(eventPublisher, never()).publishApproved(any());
	}

	@Test
	void findByReferenceFailsWhenApplicationDoesNotExist() {
		when(applications.findByReference("NOPE")).thenReturn(Mono.empty());

		StepVerifier.create(service.findByReference("NOPE"))
				.expectError(ApplicationNotFoundException.class)
				.verify();
	}
}
