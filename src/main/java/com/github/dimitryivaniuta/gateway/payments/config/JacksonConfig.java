package com.github.dimitryivaniuta.gateway.payments.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public ObjectMapper canonicalObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper;
    }
}
