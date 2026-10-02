package com.bancoias.credit.domain;

import java.math.BigDecimal;

/**
 * Datos de entrada de una solicitud de credito tal como llegan desde un canal.
 */
public record CreditRequest(
		String applicationReference,
		String customerId,
		BigDecimal amount,
		int termMonths) {
}
