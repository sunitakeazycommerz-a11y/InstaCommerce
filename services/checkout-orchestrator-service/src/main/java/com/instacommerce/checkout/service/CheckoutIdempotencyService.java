package com.instacommerce.checkout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encapsulates transactional idempotency operations for checkout orchestration,
 * ensuring short database transactions that do not span long-running Temporal workflows.
 */
@Service
public class CheckoutIdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutIdempotencyService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(30);

    private final CheckoutIdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public CheckoutIdempotencyService(CheckoutIdempotencyKeyRepository idempotencyKeyRepository,
                                      ObjectMapper objectMapper) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<CheckoutResponse> findValidResponse(String idempotencyKey) {
        Optional<CheckoutIdempotencyKey> existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            CheckoutIdempotencyKey cached = existing.get();
            if (cached.getExpiresAt().isAfter(Instant.now())) {
                log.info("Duplicate checkout detected idempotencyKey={}, returning cached response", idempotencyKey);
                return Optional.of(deserializeResponse(cached.getCheckoutResponse()));
            }
            log.info("Expired idempotency key found idempotencyKey={}, cleaning up", idempotencyKey);
            idempotencyKeyRepository.delete(cached);
            idempotencyKeyRepository.flush();
        }
        return Optional.empty();
    }

    @Transactional
    public void recordResponse(String idempotencyKey, CheckoutResponse response) {
        Instant expiresAt = Instant.now().plus(IDEMPOTENCY_TTL);
        CheckoutIdempotencyKey key = new CheckoutIdempotencyKey(
            idempotencyKey, serializeResponse(response), expiresAt);
        idempotencyKeyRepository.save(key);
    }

    private String serializeResponse(CheckoutResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize checkout response", e);
        }
    }

    private CheckoutResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, CheckoutResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached checkout response", e);
        }
    }
}

