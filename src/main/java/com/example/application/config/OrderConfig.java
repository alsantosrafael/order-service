package com.example.application.config;


import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "app.order")
public interface OrderConfig {

	@WithName("max-items")
	int maxItems();

	@WithName("default-currency")
	@WithDefault("BRL")
	String currency();

	RetryConfig retry();

	interface RetryConfig {
		@WithName("max-attempts")
		@WithDefault("3")
		int maxAttempts();
	}
}
