package com.bancoias.credit.infrastructure.web.dto;

import com.bancoias.credit.domain.CreditRequest;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Contrato de entrada. Aqui solo se valida la forma (campos presentes y con formato valido).
 * Las reglas de negocio (monto > 0, plazo 6-60, cliente habilitado, cupo) las decide el dominio
 * y generan una solicitud RECHAZADA persistida, no un error HTTP.
 */
public record CreditApplicationRequest(
		@NotBlank @Size(max = 50) String applicationReference,
		@NotBlank @Size(max = 50) String customerId,
		@NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotNull Integer termMonths) {

	public CreditRequest toDomain() {
		// @Digits garantiza maximo 2 decimales, asi que normalizar la escala nunca redondea.
		return new CreditRequest(applicationReference.trim(), customerId.trim(),
				amount.setScale(2, RoundingMode.UNNECESSARY), termMonths);
	}
}
