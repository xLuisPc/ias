package com.bancoias.credit.domain;

/**
 * Razon general de rechazo. Se persiste el codigo (nombre del enum) y la descripcion
 * se usa para mostrar un mensaje entendible al usuario.
 */
public enum RejectionReason {

	INVALID_AMOUNT("El valor solicitado debe ser mayor que cero"),
	INVALID_TERM("El plazo debe estar entre " + CreditPolicy.MIN_TERM_MONTHS + " y " + CreditPolicy.MAX_TERM_MONTHS + " meses"),
	CUSTOMER_NOT_FOUND("El cliente no existe"),
	CUSTOMER_NOT_ELIGIBLE("El cliente no esta habilitado para solicitar credito"),
	CREDIT_LIMIT_EXCEEDED("La solicitud supera el cupo maximo acumulado disponible del cliente");

	private final String description;

	RejectionReason(String description) {
		this.description = description;
	}

	public String description() {
		return description;
	}
}
