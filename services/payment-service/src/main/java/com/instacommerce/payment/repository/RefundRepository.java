package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentId(UUID paymentId);
}
