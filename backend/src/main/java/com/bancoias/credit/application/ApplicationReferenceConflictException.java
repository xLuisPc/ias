package com.bancoias.credit.application;

import com.bancoias.credit.domain.CreditApplication;

/**
 * Llego una referencia ya procesada pero con datos diferentes (RF05).
 * Se conserva la solicitud original y se informa el conflicto.
 */
public class ApplicationReferenceConflictException extends RuntimeException {

	private final CreditApplication original;

	public ApplicationReferenceConflictException(CreditApplication original) {
		super("Application reference " + original.applicationReference()
				+ " was already processed with different data");
		this.original = original;
	}

	public CreditApplication original() {
		return original;
	}
}
