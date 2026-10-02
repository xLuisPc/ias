package com.bancoias.credit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreditPolicyTest {

	private final CreditPolicy policy = new CreditPolicy();

	private static final Customer ELIGIBLE = new Customer("CLI-1001", CustomerStatus.ELIGIBLE,
			new BigDecimal("10000000"), new BigDecimal("4000000"));

	private static CreditRequest request(String amount, int termMonths) {
		return new CreditRequest("REF-1", "CLI-1001", new BigDecimal(amount), termMonths);
	}

	@Test
	void allowsValidRequestWithinAvailableCredit() {
		assertThat(policy.evaluate(request("6000000", 24), Optional.of(ELIGIBLE))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "-5000000" })
	void rejectsAmountNotGreaterThanZero(String amount) {
		assertThat(policy.evaluate(request(amount, 24), Optional.of(ELIGIBLE)))
				.contains(RejectionReason.INVALID_AMOUNT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 5, 61, 120 })
	void rejectsTermOutsideAllowedRange(int termMonths) {
		assertThat(policy.evaluate(request("1000000", termMonths), Optional.of(ELIGIBLE)))
				.contains(RejectionReason.INVALID_TERM);
	}

	@ParameterizedTest
	@ValueSource(ints = { 6, 60 })
	void acceptsTermLimits(int termMonths) {
		assertThat(policy.evaluate(request("1000000", termMonths), Optional.of(ELIGIBLE))).isEmpty();
	}

	@Test
	void rejectsUnknownCustomer() {
		assertThat(policy.evaluate(request("1000000", 24), Optional.empty()))
				.contains(RejectionReason.CUSTOMER_NOT_FOUND);
	}

	@Test
	void rejectsBlockedCustomer() {
		var blocked = new Customer("CLI-1002", CustomerStatus.BLOCKED, new BigDecimal("8000000"), BigDecimal.ZERO);
		assertThat(policy.evaluate(request("1000000", 24), Optional.of(blocked)))
				.contains(RejectionReason.CUSTOMER_NOT_ELIGIBLE);
	}

	@Test
	void allowsRequestThatUsesExactlyTheAvailableCredit() {
		assertThat(policy.evaluate(request("6000000", 24), Optional.of(ELIGIBLE))).isEmpty();
	}

	@Test
	void rejectsRequestThatExceedsAvailableCredit() {
		assertThat(policy.evaluate(request("6000000.01", 24), Optional.of(ELIGIBLE)))
				.contains(RejectionReason.CREDIT_LIMIT_EXCEEDED);
	}
}
