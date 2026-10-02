package com.bancoias.credit.application;

public class ApplicationNotFoundException extends RuntimeException {

	public ApplicationNotFoundException(String applicationReference) {
		super("Credit application not found: " + applicationReference);
	}
}
