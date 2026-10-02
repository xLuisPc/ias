package com.bancoias.credit.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reglas de aprobacion (RF02). Es logica pura, sin dependencias de infraestructura,
 * para poder probarla de forma aislada.
 *
 * La validacion de cupo que se hace aqui usa una "foto" del cliente; la garantia real
 * ante concurrencia la da la reserva atomica en base de datos (ver CustomerRepository#tryReserveCredit).
 */
public class CreditPolicy {

	public static final int MIN_TERM_MONTHS = 6;
	public static final int MAX_TERM_MONTHS = 60;

	/**
	 * @return la razon de rechazo, o vacio si la solicitud puede intentar aprobarse.
	 */
	public Optional<RejectionReason> evaluate(CreditRequest request, Optional<Customer> customer) {
		if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
			return Optional.of(RejectionReason.INVALID_AMOUNT);
		}
		if (request.termMonths() < MIN_TERM_MONTHS || request.termMonths() > MAX_TERM_MONTHS) {
			return Optional.of(RejectionReason.INVALID_TERM);
		}
		if (customer.isEmpty()) {
			return Optional.of(RejectionReason.CUSTOMER_NOT_FOUND);
		}
		if (!customer.get().isEligible()) {
			return Optional.of(RejectionReason.CUSTOMER_NOT_ELIGIBLE);
		}
		if (!customer.get().canAfford(request.amount())) {
			return Optional.of(RejectionReason.CREDIT_LIMIT_EXCEEDED);
		}
		return Optional.empty();
	}
}
