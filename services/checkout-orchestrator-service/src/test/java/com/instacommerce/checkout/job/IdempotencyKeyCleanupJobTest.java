package com.instacommerce.checkout.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdempotencyKeyCleanupJobTest {

    @Test
    void cleanupExpiredKeysUsesSevenDayRetentionCutoff() {
        CheckoutIdempotencyKeyRepository repository = mock(CheckoutIdempotencyKeyRepository.class);
        when(repository.deleteExpiredKeys(any())).thenReturn(3);
        IdempotencyKeyCleanupJob job = new IdempotencyKeyCleanupJob(repository);

        Instant before = Instant.now();
        job.cleanupExpiredKeys();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteExpiredKeys(cutoffCaptor.capture());
        Instant cutoff = cutoffCaptor.getValue();

        assertThat(CheckoutIdempotencyKey.RETENTION_TTL).isEqualTo(Duration.ofDays(7));
        assertThat(cutoff).isBetween(before.minus(Duration.ofDays(7)), after.minus(Duration.ofDays(7)));
    }

    @Test
    void defaultEntityConstructorAppliesSevenDayTtl() {
        Instant before = Instant.now();
        CheckoutIdempotencyKey key = new CheckoutIdempotencyKey("idem-1", "{\"status\":\"COMPLETED\"}");
        Instant after = Instant.now();

        assertThat(key.getExpiresAt())
            .isBetween(before.plus(Duration.ofDays(7)), after.plus(Duration.ofDays(7)));
    }
}
