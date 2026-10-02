package com.bancoias.credit.infrastructure.persistence;

import com.bancoias.credit.application.DuplicateApplicationReferenceException;
import com.bancoias.credit.application.port.CreditApplicationRepository;
import com.bancoias.credit.domain.ApplicationStatus;
import com.bancoias.credit.domain.CreditApplication;
import com.bancoias.credit.domain.RejectionReason;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcCreditApplicationRepository implements CreditApplicationRepository {

	private static final String COLUMNS = """
			id, application_reference, customer_id, amount, term_months, status, rejection_reason, processed_at""";

	private final DatabaseClient db;

	public R2dbcCreditApplicationRepository(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<CreditApplication> save(CreditApplication application) {
		var spec = db.sql("INSERT INTO credit_application (" + COLUMNS + ") VALUES "
						+ "(:id, :reference, :customerId, :amount, :termMonths, :status, :reason, :processedAt)")
				.bind("id", application.id())
				.bind("reference", application.applicationReference())
				.bind("customerId", application.customerId())
				.bind("amount", application.amount())
				.bind("termMonths", application.termMonths())
				.bind("status", application.status().name())
				.bind("processedAt", application.processedAt());
		spec = application.rejectionReason() == null
				? spec.bindNull("reason", String.class)
				: spec.bind("reason", application.rejectionReason().name());

		return spec.fetch().rowsUpdated()
				.thenReturn(application)
				// La unica restriccion que puede violarse aqui es el UNIQUE de application_reference.
				.onErrorMap(DataIntegrityViolationException.class,
						e -> new DuplicateApplicationReferenceException(application.applicationReference(), e));
	}

	@Override
	public Mono<CreditApplication> findByReference(String applicationReference) {
		return db.sql("SELECT " + COLUMNS + " FROM credit_application WHERE application_reference = :reference")
				.bind("reference", applicationReference)
				.map(R2dbcCreditApplicationRepository::toDomain)
				.one();
	}

	@Override
	public Flux<CreditApplication> findRecent(int limit) {
		return db.sql("SELECT " + COLUMNS + " FROM credit_application ORDER BY processed_at DESC LIMIT :limit")
				.bind("limit", limit)
				.map(R2dbcCreditApplicationRepository::toDomain)
				.all();
	}

	private static CreditApplication toDomain(Readable row) {
		String reason = row.get("rejection_reason", String.class);
		return new CreditApplication(
				row.get("id", UUID.class),
				row.get("application_reference", String.class),
				row.get("customer_id", String.class),
				row.get("amount", BigDecimal.class),
				row.get("term_months", Integer.class),
				ApplicationStatus.valueOf(row.get("status", String.class)),
				reason == null ? null : RejectionReason.valueOf(reason),
				row.get("processed_at", OffsetDateTime.class));
	}
}
