package com.bancoias.credit.infrastructure.web;

import com.bancoias.credit.application.CreditApplicationService;
import com.bancoias.credit.infrastructure.web.dto.CreditApplicationRequest;
import com.bancoias.credit.infrastructure.web.dto.CreditApplicationResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/credit-applications")
public class CreditApplicationController {

	/** Header que indica que la respuesta corresponde a una referencia ya procesada (RF05). */
	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final CreditApplicationService service;

	public CreditApplicationController(CreditApplicationService service) {
		this.service = service;
	}

	/**
	 * 201 Created -> solicitud nueva procesada (aprobada o rechazada).
	 * 200 OK      -> referencia repetida con los mismos datos: se devuelve el resultado original.
	 * 409 Conflict-> referencia repetida con datos diferentes.
	 */
	@PostMapping
	public Mono<ResponseEntity<CreditApplicationResponse>> process(@Valid @RequestBody CreditApplicationRequest request) {
		return service.process(request.toDomain())
				.map(result -> {
					var body = CreditApplicationResponse.from(result.application());
					if (result.replayed()) {
						return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
					}
					return ResponseEntity.status(HttpStatus.CREATED)
							.location(URI.create("/api/credit-applications/" + body.applicationReference()))
							.body(body);
				});
	}

	@GetMapping("/{applicationReference}")
	public Mono<CreditApplicationResponse> findByReference(@PathVariable String applicationReference) {
		return service.findByReference(applicationReference).map(CreditApplicationResponse::from);
	}

	@GetMapping
	public Flux<CreditApplicationResponse> findRecent(@RequestParam(defaultValue = "20") int limit) {
		return service.findRecent(limit).map(CreditApplicationResponse::from);
	}
}
