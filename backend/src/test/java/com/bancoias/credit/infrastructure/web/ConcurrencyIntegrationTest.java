package com.bancoias.credit.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoias.credit.application.port.CustomerRepository;
import com.bancoias.credit.domain.Customer;
import com.bancoias.credit.infrastructure.persistence.R2dbcCustomerRepository;
import com.bancoias.credit.infrastructure.web.dto.CreditApplicationResponse;
import com.bancoias.credit.infrastructure.web.dto.CustomerResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pruebas de concurrencia (RF04 y RF05 simultaneo).
 *
 * Con H2 en memoria cada transaccion dura microsegundos y las peticiones casi nunca se solapan,
 * asi que un test "rapido" pasaria incluso con una implementacion ingenua (leer cupo -> validar -> sumar).
 * Para que la prueba sea determinista, la lectura del cliente se retrasa: todas las peticiones leen
 * el mismo cupo "viejo" antes de que alguna confirme. Solo la reserva atomica en base de datos
 * evita aprobar de mas en ese escenario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrencyIntegrationTest {

	private static final String API = "/api/credit-applications";

	@TestConfiguration
	static class SlowCustomerReadsConfig {

		@Bean
		@Primary
		CustomerRepository slowReadsCustomerRepository(R2dbcCustomerRepository delegate) {
			return new CustomerRepository() {
				@Override
				public Mono<Customer> findById(String customerId) {
					return delegate.findById(customerId).delayElement(Duration.ofMillis(300));
				}

				@Override
				public Flux<Customer> findAll() {
					return delegate.findAll();
				}

				@Override
				public Mono<Boolean> tryReserveCredit(String customerId, BigDecimal amount) {
					return delegate.tryReserveCredit(customerId, amount);
				}
			};
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private DatabaseClient db;

	private WebClient webClient;

	@BeforeEach
	void setUp() {
		webClient = WebClient.create("http://localhost:" + port);
		db.sql("DELETE FROM credit_application").then()
				.then(db.sql("UPDATE customer SET approved_amount = 0").then())
				.block();
	}

	@Test
	void concurrentRequestsForSameCustomerNeverExceedTheLimit() throws Exception {
		// 10 solicitudes simultaneas de 3.000.000 contra un cupo de 10.000.000 -> solo 3 caben.
		List<CreditApplicationResponse> responses = sendSimultaneously(10, i -> webClient.post().uri(API)
				.bodyValue(body("REF-C-" + i, "CLI-1001", 3_000_000))
				.retrieve()
				.bodyToMono(CreditApplicationResponse.class)
				.block());

		assertThat(responses).filteredOn(r -> r.status().equals("APPROVED")).hasSize(3);
		assertThat(responses).filteredOn(r -> "CREDIT_LIMIT_EXCEEDED".equals(r.rejectionReason())).hasSize(7);
		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("9000000");
	}

	@Test
	void concurrentRequestsWithSameReferenceCreateASingleApplication() throws Exception {
		// Todas pasan la verificacion inicial de "referencia no existe"; el UNIQUE decide la ganadora
		// y las perdedoras revierten su reserva de cupo y devuelven el resultado original.
		List<Integer> statuses = sendSimultaneously(10, i -> webClient.post().uri(API)
				.bodyValue(body("REF-DUP", "CLI-2001", 2_000_000))
				.exchangeToMono(response -> Mono.just(response.statusCode().value()))
				.block());

		assertThat(statuses).containsOnly(201, 200);
		assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
		assertThat(approvedAmountOf("CLI-2001")).isEqualByComparingTo("2000000");
		assertThat(webClient.get().uri(API).retrieve().bodyToFlux(CreditApplicationResponse.class)
				.collectList().block()).hasSize(1);
	}

	private static Map<String, Object> body(String reference, String customerId, long amount) {
		return Map.of("applicationReference", reference, "customerId", customerId, "amount", amount,
				"termMonths", 24);
	}

	private BigDecimal approvedAmountOf(String customerId) {
		return webClient.get().uri("/api/customers").retrieve().bodyToFlux(CustomerResponse.class)
				.filter(c -> c.id().equals(customerId))
				.blockFirst()
				.approvedAmount();
	}

	/** Lanza las peticiones desde hilos distintos liberados al mismo tiempo por una barrera. */
	private static <T> List<T> sendSimultaneously(int requests, IntFunction<T> call) throws Exception {
		var startGate = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(requests)) {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 1; i <= requests; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					startGate.await();
					return call.apply(index);
				}));
			}
			startGate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}
}
