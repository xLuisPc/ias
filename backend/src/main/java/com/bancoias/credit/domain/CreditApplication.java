package com.bancoias.credit.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de credito ya procesada: conserva los datos principales, el resultado,
 * la razon (si fue rechazada) y la fecha/hora de procesamiento.
 */
public record CreditApplication(
		UUID id,
		String applicationReference,
		String customerId,
		BigDecimal amount,
		int termMonths,
		ApplicationStatus status,
		RejectionReason rejectionReason,
		OffsetDateTime processedAt) {

	public static CreditApplication approved(CreditRequest request, OffsetDateTime processedAt) {
		return of(request, ApplicationStatus.APPROVED, null, processedAt);
	}

	public static CreditApplication rejected(CreditRequest request, RejectionReason reason, OffsetDateTime processedAt) {
		return of(request, ApplicationStatus.REJECTED, Objects.requireNonNull(reason), processedAt);
	}

	private static CreditApplication of(CreditRequest request, ApplicationStatus status, RejectionReason reason,
			OffsetDateTime processedAt) {
		return new CreditApplication(UUID.randomUUID(), request.applicationReference(), request.customerId(),
				request.amount(), request.termMonths(), status, reason, processedAt);
	}

	public boolean isApproved() {
		return status == ApplicationStatus.APPROVED;
	}

	/**
	 * Indica si una solicitud entrante con la misma referencia trae exactamente la misma informacion.
	 * El monto se compara con compareTo para que 6000000 y 6000000.00 se consideren iguales.
	 */
	public boolean hasSameContentAs(CreditRequest request) {
		return customerId.equals(request.customerId())
				&& amount.compareTo(request.amount()) == 0
				&& termMonths == request.termMonths();
	}
}
