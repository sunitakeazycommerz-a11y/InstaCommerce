package com.instacommerce.checkout.repository;

import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CheckoutIdempotencyKeyRepository extends JpaRepository<CheckoutIdempotencyKey, UUID> {

    Optional<CheckoutIdempotencyKey> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("DELETE FROM CheckoutIdempotencyKey k WHERE k.expiresAt < :cutoff")
    int deleteExpiredKeys(Instant cutoff);
}
