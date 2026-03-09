package com.instacommerce.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared normalizer for idempotency keys used in payment and refund flows.
 * <p>
 * The {@code payments.idempotency_key} and {@code refunds.idempotency_key}
 * columns are {@code VARCHAR(64)}. Callers (e.g. checkout orchestration) may
 * supply keys longer than 64 characters. This helper deterministically
 * compresses oversized keys with SHA-256 (which produces exactly 64 lowercase
 * hex characters) so that lookups and inserts never exceed the column limit
 * while remaining collision-resistant and stable across retries.
 */
public final class IdempotencyKeys {

    static final int MAX_LENGTH = 64;

    private IdempotencyKeys() {}

    /**
     * Normalizes a raw idempotency key for safe storage and lookup.
     * <ul>
     *   <li>null / blank → null (caller decides whether to generate a fallback)</li>
     *   <li>length ≤ 64 after trim → trimmed value returned as-is</li>
     *   <li>length &gt; 64 after trim → SHA-256 hex digest (64 lowercase hex chars)</li>
     * </ul>
     */
    public static String normalize(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() <= MAX_LENGTH) {
            return trimmed;
        }
        return sha256Hex(trimmed);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java specification; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
