package com.bancoias.credit.infrastructure.persistence;

import com.bancoias.credit.application.port.CustomerRepository;
import com.bancoias.credit.domain.Customer;
import com.bancoias.credit.domain.CustomerStatus;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcCustomerRepository implements CustomerRepository {

	private static final String COLUMNS = "id, status, credit_limit, approved_amount";

	/**
	 * Reserva atomica de cupo (RF04). La condicion y la suma se evaluan en una sola sentencia:
	 * la base de datos bloquea la fila del cliente, de modo que dos solicitudes simultaneas
	 * se serializan y la segunda ve el valor ya actualizado por la primera.
	 * Si no se actualiza ninguna fila, no habia cupo suficiente (o el cliente ya no esta habilitado).
	 */
	private static final String RESERVE_CREDIT = """
			UPDATE customer
			   SET approved_amount = approved_amount + :amount
			 WHERE id = :id
			   AND status = 'ELIGIBLE'
			   AND approved_amount + :amount <= credit_limit""";

	private final DatabaseClient db;

	public R2dbcCustomerRepository(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<Customer> findById(String customerId) {
		return db.sql("SELECT " + COLUMNS + " FROM customer WHERE id = :id")
				.bind("id", customerId)
				.map(R2dbcCustomerRepository::toDomain)
				.one();
	}

	@Override
	public Flux<Customer> findAll() {
		return db.sql("SELECT " + COLUMNS + " FROM customer ORDER BY id")
				.map(R2dbcCustomerRepository::toDomain)
				.all();
	}

	@Override
	public Mono<Boolean> tryReserveCredit(String customerId, BigDecimal amount) {
		return db.sql(RESERVE_CREDIT)
				.bind("id", customerId)
				.bind("amount", amount)
				.fetch().rowsUpdated()
				.map(updatedRows -> updatedRows == 1);
	}

	private static Customer toDomain(Readable row) {
		return new Customer(
				row.get("id", String.class),
				CustomerStatus.valueOf(row.get("status", String.class)),
				row.get("credit_limit", BigDecimal.class),
				row.get("approved_amount", BigDecimal.class));
	}
}
