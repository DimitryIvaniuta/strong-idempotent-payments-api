package com.github.dimitryivaniuta.gateway.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson configuration.
 *
 * <p>We use a dedicated "canonical" ObjectMapper for request hashing:
 * properties are sorted to ensure stable JSON representation.</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * Canonical ObjectMapper used for deterministic request hashing.
     *
     * @return canonical mapper
     */
    @Bean("canonicalObjectMapper")
    public ObjectMapper canonicalObjectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper om = builder.createXmlMapper(false).build();
        om.registerModule(new JavaTimeModule());
        return om;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer javaTimeModule() {
        return builder -> builder.modules(new JavaTimeModule());
    }
}
