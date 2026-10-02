package com.bancoias.credit.infrastructure.web;

import com.bancoias.credit.application.CreditApplicationService;
import com.bancoias.credit.infrastructure.web.dto.CustomerResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Solo lectura: permite ver el cupo disponible de los clientes semilla para facilitar las pruebas.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	private final CreditApplicationService service;

	public CustomerController(CreditApplicationService service) {
		this.service = service;
	}

	@GetMapping
	public Flux<CustomerResponse> findAll() {
		return service.findCustomers().map(CustomerResponse::from);
	}
}
