package com.stockflow;

import com.stockflow.identity.adapter.in.bootstrap.AdminBootstrapApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StockflowApplication {

	public static void main(String[] args) {
		// Modo explícito de bootstrap del primer ADMIN: proceso aparte, sin servidor web, que termina al acabar.
		if (AdminBootstrapApplication.isRequested(args)) {
			AdminBootstrapApplication.run(args);
			return;
		}
		SpringApplication.run(StockflowApplication.class, args);
	}

}
