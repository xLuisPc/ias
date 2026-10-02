package com.bancoias.credit.domain;

import java.math.BigDecimal;

/**
 * Cliente con su cupo maximo acumulado (creditLimit) y el valor ya aprobado (approvedAmount).
 */
public record Customer(
		String id,
		CustomerStatus status,
		BigDecimal creditLimit,
		BigDecimal approvedAmount) {

	public boolean isEligible() {
		return status == CustomerStatus.ELIGIBLE;
	}

	public BigDecimal availableAmount() {
		return creditLimit.subtract(approvedAmount);
	}

	public boolean canAfford(BigDecimal amount) {
		return amount.compareTo(availableAmount()) <= 0;
	}
}
