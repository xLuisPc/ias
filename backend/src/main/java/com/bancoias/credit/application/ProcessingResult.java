package com.bancoias.credit.application;

import com.bancoias.credit.domain.CreditApplication;

/**
 * Resultado de procesar una solicitud.
 *
 * @param replayed true cuando la referencia ya habia sido procesada y se devuelve el resultado original.
 */
public record ProcessingResult(CreditApplication application, boolean replayed) {

	public static ProcessingResult created(CreditApplication application) {
		return new ProcessingResult(application, false);
	}

	public static ProcessingResult replayed(CreditApplication application) {
		return new ProcessingResult(application, true);
	}
}
