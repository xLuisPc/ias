package com.bancoias.credit.infrastructure.web.dto;

import com.bancoias.credit.domain.CreditApplication;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreditApplicationResponse(
		String applicationReference,
		String customerId,
		BigDecimal amount,
		int termMonths,
		String status,
		String rejectionReason,
		String message,
		OffsetDateTime processedAt) {

	public static CreditApplicationResponse from(CreditApplication application) {
		var reason = application.rejectionReason();
		return new CreditApplicationResponse(
				application.applicationReference(),
				application.customerId(),
				application.amount(),
				application.termMonths(),
				application.status().name(),
				reason == null ? null : reason.name(),
				reason == null ? "Solicitud aprobada" : reason.description(),
				application.processedAt());
	}
}
