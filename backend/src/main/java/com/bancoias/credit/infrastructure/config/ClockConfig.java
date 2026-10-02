package com.bancoias.credit.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	/** Reloj inyectable (UTC) para registrar la fecha de procesamiento y poder fijarla en pruebas. */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
