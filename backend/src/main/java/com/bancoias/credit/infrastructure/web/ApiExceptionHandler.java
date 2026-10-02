package com.bancoias.credit.infrastructure.web;

import com.bancoias.credit.application.ApplicationNotFoundException;
import com.bancoias.credit.application.ApplicationReferenceConflictException;
import com.bancoias.credit.infrastructure.web.dto.CreditApplicationResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Traduce excepciones a respuestas ProblemDetail (RFC 9457). Hereda el manejo estandar de
 * errores de Spring (JSON mal formado, metodo no soportado, etc.).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApplicationNotFoundException.class)
	public ProblemDetail handleNotFound(ApplicationNotFoundException ex) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Solicitud no encontrada");
		return problem;
	}

	@ExceptionHandler(ApplicationReferenceConflictException.class)
	public ProblemDetail handleConflict(ApplicationReferenceConflictException ex) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"La referencia ya fue procesada con datos diferentes. Se conserva la solicitud original.");
		problem.setTitle("Referencia de solicitud en conflicto");
		problem.setProperty("originalApplication", CreditApplicationResponse.from(ex.original()));
		return problem;
	}

	/** Agrega el detalle por campo a los errores de validacion. */
	@Override
	protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(WebExchangeBindException ex,
			HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

		var problem = ex.getBody();
		problem.setTitle("Solicitud invalida");
		problem.setDetail("Uno o mas campos no son validos");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, exchange);
	}
}
