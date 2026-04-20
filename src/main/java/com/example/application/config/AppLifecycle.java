package com.example.application.config;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class AppLifecycle {

	private static final Logger LOG = LoggerFactory.getLogger(AppLifecycle.class);

	void onStartup(@Observes StartupEvent event) {
		LOG.info("Order service started");
	}

	void onShutdown(@Observes ShutdownEvent event) {
		LOG.info("Order service stopped. Flushing in-flight work");
	}
}
