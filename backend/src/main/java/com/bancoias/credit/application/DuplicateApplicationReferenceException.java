package com.bancoias.credit.application;

/**
 * La base de datos rechazo la insercion porque la referencia ya existe
 * (dos solicitudes con la misma referencia llegaron casi al mismo tiempo).
 */
public class DuplicateApplicationReferenceException extends RuntimeException {

	public DuplicateApplicationReferenceException(String applicationReference, Throwable cause) {
		super("Application reference already exists: " + applicationReference, cause);
	}
}
