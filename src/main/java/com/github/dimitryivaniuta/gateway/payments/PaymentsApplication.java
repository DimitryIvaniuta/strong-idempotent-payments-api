package com.github.dimitryivaniuta.gateway.payments;

import com.github.dimitryivaniuta.gateway.payments.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point for the Strong Idempotent Payments API.
 */
@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableConfigurationProperties(AppProperties.class)
public class PaymentsApplication {

    /**
     * Bootstraps the Spring Boot application.
     *
     * @param args CLI args
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
