package com.bancoias.credit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bancoias.credit.infrastructure.web.dto.CreditApplicationResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

/**
 * Punto opcional RabbitMQ contra un broker real (Testcontainers): publicacion, consumo idempotente,
 * reintentos y dead-letter queue. Se omite si no hay Docker, para que la suite obligatoria no dependa de el.
 *
 * El contenedor de listeners usa un solo consumidor (valor por defecto), que procesa los mensajes en orden.
 * Cuando un mensaje "barrera" enviado al final ya quedo registrado, los anteriores tambien fueron procesados:
 * asi se comprueba que algo NO ocurrio sin depender de esperas fijas.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.rabbitmq.enabled=true",
		"spring.rabbitmq.listener.simple.retry.initial-interval=50ms" })
@ActiveProfiles("test")
class RabbitMessagingIntegrationTest {

	private static final String API = "/api/credit-applications";
	private static final Duration MAX_WAIT = Duration.ofSeconds(10);

	@TestConfiguration(proxyBeanMethods = false)
	static class RabbitContainerConfig {

		@Bean
		@ServiceConnection
		RabbitMQContainer rabbitContainer() {
			return new RabbitMQContainer("rabbitmq:4-management");
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private DatabaseClient db;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@MockitoSpyBean
	private ApprovedApplicationAuditRepository audit;

	@Value("${app.rabbitmq.exchange}")
	private String exchange;

	@Value("${app.rabbitmq.approved-routing-key}")
	private String routingKey;

	@Value("${app.rabbitmq.approved-dead-letter-queue}")
	private String deadLetterQueue;

	private WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		db.sql("DELETE FROM credit_application").then()
				.then(db.sql("DELETE FROM approved_application_audit").then())
				.then(db.sql("UPDATE customer SET approved_amount = 0").then())
				.block();
		amqpAdmin.purgeQueue(deadLetterQueue, false);
	}

	@Test
	void approvedApplicationIsPublishedAndAudited() {
		var response = post("REF-MQ-001", "CLI-1001");
		assertThat(response.status()).isEqualTo("APPROVED");

		awaitAudited("REF-MQ-001");
		var row = db.sql("SELECT customer_id, amount FROM approved_application_audit WHERE message_id = :id")
				.bind("id", "REF-MQ-001")
				.map(r -> Map.of("customerId", r.get("customer_id", String.class),
						"amount", r.get("amount", BigDecimal.class)))
				.one().block();
		assertThat(row.get("customerId")).isEqualTo("CLI-1001");
		assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1000000");
	}

	@Test
	void rejectedApplicationIsNotPublished() {
		var rejected = post("REF-MQ-REJ", "CLI-1002"); // cliente bloqueado
		assertThat(rejected.status()).isEqualTo("REJECTED");
		post("REF-MQ-BARRIER", "CLI-1001");

		awaitAudited("REF-MQ-BARRIER");
		assertThat(auditedCount("REF-MQ-REJ")).isZero();
	}

	@Test
	void duplicateMessageIsAuditedOnceWithoutRetries() {
		send(event("REF-MQ-DUP"), "REF-MQ-DUP");
		send(event("REF-MQ-DUP"), "REF-MQ-DUP");
		send(event("REF-MQ-BARRIER"), "REF-MQ-BARRIER");

		awaitAudited("REF-MQ-BARRIER");
		assertThat(auditedCount("REF-MQ-DUP")).isEqualTo(1);
		// Dos entregas, dos invocaciones: el duplicado se descarto sin error. Si fallara, habria reintentos.
		verify(audit, times(2)).recordOnce(eq("REF-MQ-DUP"), any());
	}

	@Test
	void transientFailureIsRetriedAndThenProcessed() {
		doReturn(Mono.error(new IllegalStateException("Falla transitoria simulada")))
				.doCallRealMethod()
				.when(audit).recordOnce(eq("REF-MQ-FLAKY"), any());

		send(event("REF-MQ-FLAKY"), "REF-MQ-FLAKY");

		awaitAudited("REF-MQ-FLAKY");
		verify(audit, times(2)).recordOnce(eq("REF-MQ-FLAKY"), any());
		assertThat(rabbitTemplate.receive(deadLetterQueue)).isNull();
	}

	@Test
	void persistentFailureEndsInDeadLetterQueueAfterRetries() {
		doReturn(Mono.error(new IllegalStateException("Falla permanente simulada")))
				.when(audit).recordOnce(eq("REF-MQ-POISON"), any());

		send(event("REF-MQ-POISON"), "REF-MQ-POISON");

		Message dead = rabbitTemplate.receive(deadLetterQueue, MAX_WAIT.toMillis());
		assertThat(dead).isNotNull();
		assertThat(dead.getMessageProperties().getMessageId()).isEqualTo("REF-MQ-POISON");
		verify(audit, times(3)).recordOnce(eq("REF-MQ-POISON"), any()); // 1 intento + 2 reintentos
		assertThat(auditedCount("REF-MQ-POISON")).isZero();
	}

	@Test
	void messageWithoutIdBreaksContractAndEndsInDeadLetterQueue() {
		rabbitTemplate.convertAndSend(exchange, routingKey, event("REF-MQ-NOID"));

		assertThat(rabbitTemplate.receive(deadLetterQueue, MAX_WAIT.toMillis())).isNotNull();
		verify(audit, never()).recordOnce(any(), any());
	}

	private CreditApplicationResponse post(String reference, String customerId) {
		var body = Map.of("applicationReference", reference, "customerId", customerId, "amount", 1_000_000,
				"termMonths", 12);
		return client.post().uri(API).bodyValue(body)
				.exchange()
				.expectStatus().isCreated()
				.expectBody(CreditApplicationResponse.class)
				.returnResult().getResponseBody();
	}

	private static CreditApplicationApprovedEvent event(String reference) {
		return new CreditApplicationApprovedEvent(CreditApplicationApprovedEvent.TYPE, reference, "CLI-1001",
				new BigDecimal("1000000.00"), 12, OffsetDateTime.now());
	}

	private void send(CreditApplicationApprovedEvent event, String messageId) {
		rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
			message.getMessageProperties().setMessageId(messageId);
			return message;
		});
	}

	private void awaitAudited(String reference) {
		await().atMost(MAX_WAIT).until(() -> auditedCount(reference) == 1);
	}

	private long auditedCount(String reference) {
		return db.sql("SELECT COUNT(*) AS total FROM approved_application_audit WHERE application_reference = :ref")
				.bind("ref", reference)
				.map(row -> row.get("total", Long.class))
				.one().block();
	}
}
