package com.bancoias.credit.application.port;

import com.bancoias.credit.domain.CreditApplication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CreditApplicationRepository {

	/**
	 * Persiste la solicitud. Si la referencia ya existe emite
	 * {@link com.bancoias.credit.application.DuplicateApplicationReferenceException}.
	 */
	Mono<CreditApplication> save(CreditApplication application);

	Mono<CreditApplication> findByReference(String applicationReference);

	Flux<CreditApplication> findRecent(int limit);
}
