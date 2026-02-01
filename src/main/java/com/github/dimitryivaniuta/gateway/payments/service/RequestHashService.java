package com.github.dimitryivaniuta.gateway.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Computes a stable request hash for idempotency.
 *
 * <p>The hash is SHA-256 over canonical JSON representation and is Base64-encoded.</p>
 */
@Service
public class RequestHashService {

    private final ObjectMapper canonicalObjectMapper;

    /**
     * Creates the service.
     *
     * @param canonicalObjectMapper canonical mapper
     */
    public RequestHashService(@Qualifier("canonicalObjectMapper") ObjectMapper canonicalObjectMapper) {
        this.canonicalObjectMapper = canonicalObjectMapper;
    }

    /**
     * Computes Base64(SHA-256(canonicalJson(payload))).
     *
     * @param payload request payload
     * @return request hash
     */
    public String hash(Object payload) {
        try {
            byte[] json = canonicalObjectMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest(json);
            return Base64.getEncoder().encodeToString(h);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize request for hashing", e);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute request hash", e);
        }
    }

    /**
     * Produces a deterministic string suitable for logging.
     *
     * @param payload payload
     * @return canonical json string
     */
    public String canonicalJson(Object payload) {
        try {
            return canonicalObjectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize payload", e);
        }
    }
}
