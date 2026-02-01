package com.github.dimitryivaniuta.gateway.payments.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic configuration.
 */
@Configuration
public class KafkaConfig {

    /**
     * Creates the payments events topic when using auto topic creation disabled.
     *
     * <p>In many production environments topics are provisioned by IaC (Terraform/Helm).
     * This bean is safe to keep for local/dev environments.</p>
     *
     * @param props application properties
     * @return topic definition
     */
    @Bean
    public NewTopic paymentsEventsTopic(AppProperties props) {
        return TopicBuilder.name(props.getOutbox().getPaymentsEventsTopic())
                .partitions(6)
                .replicas(1)
                .build();
    }
}
