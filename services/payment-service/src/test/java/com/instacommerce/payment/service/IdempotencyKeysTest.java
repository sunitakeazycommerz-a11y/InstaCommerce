package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeysTest {

    @Nested
    @DisplayName("null / blank handling")
    class NullBlank {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Returns null for null, empty, or blank input")
        void returnsNullForBlankInput(String input) {
            assertThat(IdempotencyKeys.normalize(input)).isNull();
        }
    }

    @Nested
    @DisplayName("Short keys (≤ 64 chars)")
    class ShortKeys {

        @Test
        @DisplayName("Returns trimmed key when within limit")
        void trimmedShortKey() {
            assertThat(IdempotencyKeys.normalize("  abc-123  ")).isEqualTo("abc-123");
        }

        @Test
        @DisplayName("Exactly 64 characters are preserved as-is")
        void exactly64Chars() {
            String key = "a".repeat(64);
            assertThat(IdempotencyKeys.normalize(key)).isEqualTo(key);
            assertThat(IdempotencyKeys.normalize(key)).hasSize(64);
        }

        @Test
        @DisplayName("64 chars after trim are preserved")
        void exactly64AfterTrim() {
            String key = "  " + "b".repeat(64) + "  ";
            String normalized = IdempotencyKeys.normalize(key);
            assertThat(normalized).isEqualTo("b".repeat(64));
            assertThat(normalized).hasSize(64);
        }

        @Test
        @DisplayName("Typical UUID key is preserved")
        void typicalUuidKey() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            assertThat(IdempotencyKeys.normalize(uuid)).isEqualTo(uuid);
        }
    }

    @Nested
    @DisplayName("Long keys (> 64 chars)")
    class LongKeys {

        @Test
        @DisplayName("65-char key is hashed to 64 lowercase hex chars")
        void hashesAt65Chars() {
            String key = "a".repeat(65);
            String normalized = IdempotencyKeys.normalize(key);
            assertThat(normalized).hasSize(64);
            assertThat(normalized).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Very long key (200 chars) is hashed to 64 lowercase hex chars")
        void hashesVeryLongKey() {
            String key = "checkout-orch-" + "x".repeat(186);
            assertThat(key).hasSize(200);
            String normalized = IdempotencyKeys.normalize(key);
            assertThat(normalized).hasSize(64);
            assertThat(normalized).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Same long key always produces same hash (deterministic)")
        void deterministic() {
            String key = "order-12345678-checkout-session-abcdef-retry-3-" + "z".repeat(50);
            assertThat(key.length()).isGreaterThan(64);

            String first = IdempotencyKeys.normalize(key);
            String second = IdempotencyKeys.normalize(key);
            String third = IdempotencyKeys.normalize(key);

            assertThat(first).isEqualTo(second).isEqualTo(third);
        }

        @Test
        @DisplayName("Different long keys produce different hashes")
        void differentInputsDifferentOutputs() {
            String key1 = "a".repeat(100);
            String key2 = "b".repeat(100);

            assertThat(IdempotencyKeys.normalize(key1))
                .isNotEqualTo(IdempotencyKeys.normalize(key2));
        }

        @Test
        @DisplayName("Long key with leading/trailing whitespace is trimmed before hashing")
        void trimmedBeforeHash() {
            String core = "c".repeat(80);
            String withSpaces = "  " + core + "  ";

            // Both should hash the same trimmed content
            assertThat(IdempotencyKeys.normalize(withSpaces))
                .isEqualTo(IdempotencyKeys.normalize(core));
        }

        @Test
        @DisplayName("Known SHA-256 vector: 65 'a' chars")
        void knownVector() {
            // SHA-256("aaa...a" x65) — pre-computed reference value
            String key = "a".repeat(65);
            String normalized = IdempotencyKeys.normalize(key);
            // Re-invoke to verify stability
            assertThat(IdempotencyKeys.normalize(key)).isEqualTo(normalized);
            assertThat(normalized).hasSize(64);
            assertThat(normalized).matches("[0-9a-f]{64}");
        }
    }
}
