package com.bellgado.logistics_ted;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("com.bellgado.logistics_ted.domain")
@EnableJpaRepositories("com.bellgado.logistics_ted.repository")
public class LogisticsTedApplication {

	public static void main(String[] args) {
		// Force UTC before the PostgreSQL JDBC driver reads TimeZone.getDefault().
		// Otherwise on Windows hosts the JVM may resolve to the legacy "Europe/Kiev"
		// identifier, which modern PostgreSQL rejects during the connection handshake.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(LogisticsTedApplication.class, args);
	}

}
