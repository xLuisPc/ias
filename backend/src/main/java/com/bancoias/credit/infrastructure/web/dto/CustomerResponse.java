package com.bancoias.credit.infrastructure.web.dto;

import com.bancoias.credit.domain.Customer;
import java.math.BigDecimal;

public record CustomerResponse(
		String id,
		String status,
		BigDecimal creditLimit,
		BigDecimal approvedAmount,
		BigDecimal availableAmount) {

	public static CustomerResponse from(Customer customer) {
		return new CustomerResponse(customer.id(), customer.status().name(), customer.creditLimit(),
				customer.approvedAmount(), customer.availableAmount());
	}
}
