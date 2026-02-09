package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentId(UUID paymentId);

    @Query("""
        select coalesce(sum(r.amountCents), 0)
        from Refund r
        where r.paymentId = :paymentId
          and r.status = com.instacommerce.payment.domain.model.RefundStatus.PENDING
        """)
    long sumPendingAmountByPaymentId(@Param("paymentId") UUID paymentId);
}
