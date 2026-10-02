package com.bancoias.credit.application.port;

import com.bancoias.credit.domain.Customer;
import java.math.BigDecimal;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CustomerRepository {

	Mono<Customer> findById(String customerId);

	Flux<Customer> findAll();

	/**
	 * Reserva cupo de forma atomica: solo suma el monto si el cliente sigue habilitado
	 * y el nuevo total no supera su cupo maximo.
	 *
	 * @return true si el cupo fue reservado, false si no habia cupo suficiente.
	 */
	Mono<Boolean> tryReserveCredit(String customerId, BigDecimal amount);
}
