package com.bancoias.credit.infrastructure.messaging;

import com.bancoias.credit.domain.CreditApplication;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Contrato del mensaje publicado cuando una solicitud es aprobada. */
public record CreditApplicationApprovedEvent(
		String eventType,
		String applicationReference,
		String customerId,
		BigDecimal amount,
		int termMonths,
		OffsetDateTime approvedAt) {

	public static final String TYPE = "CreditApplicationApproved";

	public static CreditApplicationApprovedEvent from(CreditApplication application) {
		return new CreditApplicationApprovedEvent(TYPE, application.applicationReference(), application.customerId(),
				application.amount(), application.termMonths(), application.processedAt());
	}
}
