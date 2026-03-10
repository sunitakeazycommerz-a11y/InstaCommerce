package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByPspRefundId(String pspRefundId);

    List<Refund> findByPaymentId(UUID paymentId);

    @Modifying(clearAutomatically = true)
    @Query("""
        update Refund r
           set r.pspRefundId = :pspRefundId
         where r.id = :refundId
           and r.pspRefundId is null
        """)
    int setPspRefundIdIfMissing(@Param("refundId") UUID refundId, @Param("pspRefundId") String pspRefundId);

    @Query("""
        select coalesce(sum(r.amountCents), 0)
        from Refund r
        where r.paymentId = :paymentId
          and r.status = com.instacommerce.payment.domain.model.RefundStatus.PENDING
        """)
    long sumPendingAmountByPaymentId(@Param("paymentId") UUID paymentId);

    @Query("SELECT r FROM Refund r WHERE r.status = :status AND r.updatedAt < :cutoff ORDER BY r.updatedAt ASC")
    List<Refund> findStalePendingRefunds(
        @Param("status") RefundStatus status,
        @Param("cutoff") Instant cutoff,
        Pageable pageable
    );
}
