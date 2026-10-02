package com.bancoias.credit.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoias.credit.infrastructure.web.dto.CreditApplicationResponse;
import com.bancoias.credit.infrastructure.web.dto.CustomerResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pruebas de extremo a extremo sobre HTTP real + base de datos H2 en memoria.
 * Las pruebas de concurrencia estan en {@link ConcurrencyIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CreditApplicationApiIntegrationTest {

	private static final String API = "/api/credit-applications";

	@LocalServerPort
	private int port;

	@Autowired
	private DatabaseClient db;

	private WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		db.sql("DELETE FROM credit_application").then()
				.then(db.sql("UPDATE customer SET approved_amount = 0").then())
				.block();
	}

	private static Map<String, Object> body(String reference, String customerId, long amount, int termMonths) {
		return Map.of("applicationReference", reference, "customerId", customerId, "amount", amount,
				"termMonths", termMonths);
	}

	private CreditApplicationResponse post(Map<String, Object> body, int expectedStatus) {
		return client.post().uri(API).bodyValue(body)
				.exchange()
				.expectStatus().isEqualTo(expectedStatus)
				.expectBody(CreditApplicationResponse.class)
				.returnResult().getResponseBody();
	}

	private BigDecimal approvedAmountOf(String customerId) {
		return client.get().uri("/api/customers").exchange()
				.expectBodyList(CustomerResponse.class).returnResult().getResponseBody().stream()
				.filter(c -> c.id().equals(customerId))
				.findFirst().orElseThrow()
				.approvedAmount();
	}

	@Test
	void approvesRequestWithinLimitAndConsumesCredit() {
		var response = post(body("REF-001", "CLI-1001", 6_000_000, 24), 201);

		assertThat(response.status()).isEqualTo("APPROVED");
		assertThat(response.rejectionReason()).isNull();
		assertThat(response.processedAt()).isNotNull();
		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("6000000");
	}

	@Test
	void rejectedRequestDoesNotConsumeCredit() {
		post(body("REF-001", "CLI-1001", 6_000_000, 24), 201);

		var exceeded = post(body("REF-002", "CLI-1001", 5_000_000, 24), 201);
		assertThat(exceeded.status()).isEqualTo("REJECTED");
		assertThat(exceeded.rejectionReason()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("6000000");

		// El cupo restante (4.000.000) sigue disponible porque el rechazo no lo consumio.
		var exactRemaining = post(body("REF-003", "CLI-1001", 4_000_000, 24), 201);
		assertThat(exactRemaining.status()).isEqualTo("APPROVED");
		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("10000000");
	}

	@Test
	void rejectsBlockedAndUnknownCustomersAndKeepsTraceability() {
		var blocked = post(body("REF-B", "CLI-1002", 1_000_000, 12), 201);
		var unknown = post(body("REF-U", "CLI-9999", 1_000_000, 12), 201);

		assertThat(blocked.rejectionReason()).isEqualTo("CUSTOMER_NOT_ELIGIBLE");
		assertThat(unknown.rejectionReason()).isEqualTo("CUSTOMER_NOT_FOUND");

		client.get().uri(API + "/REF-B").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("REJECTED")
				.jsonPath("$.rejectionReason").isEqualTo("CUSTOMER_NOT_ELIGIBLE")
				.jsonPath("$.message").isNotEmpty()
				.jsonPath("$.processedAt").isNotEmpty();
	}

	@Test
	void repeatedReferenceWithSameDataReturnsOriginalWithoutConsumingCreditAgain() {
		var original = post(body("REF-001", "CLI-1001", 6_000_000, 24), 201);

		var replayed = client.post().uri(API).bodyValue(body("REF-001", "CLI-1001", 6_000_000, 24))
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(CreditApplicationController.REPLAYED_HEADER, "true")
				.expectBody(CreditApplicationResponse.class)
				.returnResult().getResponseBody();

		assertThat(replayed.status()).isEqualTo("APPROVED");
		assertThat(replayed.processedAt()).isEqualTo(original.processedAt());

		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("6000000");
	}

	@Test
	void repeatedReferenceWithDifferentDataReturnsConflictAndPreservesOriginal() {
		post(body("REF-001", "CLI-1001", 6_000_000, 24), 201);

		client.post().uri(API).bodyValue(body("REF-001", "CLI-1001", 1_000_000, 12))
				.exchange()
				.expectStatus().isEqualTo(409)
				.expectBody()
				.jsonPath("$.originalApplication.amount").isEqualTo(6000000.00);

		client.get().uri(API + "/REF-001").exchange()
				.expectBody()
				.jsonPath("$.amount").isEqualTo(6000000.00)
				.jsonPath("$.termMonths").isEqualTo(24);
		assertThat(approvedAmountOf("CLI-1001")).isEqualByComparingTo("6000000");
	}

	@Test
	void listsRecentApplicationsNewestFirstRespectingLimit() {
		post(body("REF-1", "CLI-1001", 1_000_000, 12), 201);
		post(body("REF-2", "CLI-1001", 1_000_000, 12), 201);
		post(body("REF-3", "CLI-1001", 1_000_000, 12), 201);

		client.get().uri(API + "?limit=2").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2)
				.jsonPath("$[0].applicationReference").isEqualTo("REF-3")
				.jsonPath("$[1].applicationReference").isEqualTo("REF-2");
	}

	@Test
	void returnsNotFoundForUnknownReference() {
		client.get().uri(API + "/NOPE").exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentType("application/problem+json");
	}

	@Test
	void returnsBadRequestWithFieldErrorsForMalformedPayload() {
		client.post().uri(API).bodyValue(Map.of("customerId", "CLI-1001"))
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors.applicationReference").exists()
				.jsonPath("$.errors.amount").exists()
				.jsonPath("$.errors.termMonths").exists();
	}
}
