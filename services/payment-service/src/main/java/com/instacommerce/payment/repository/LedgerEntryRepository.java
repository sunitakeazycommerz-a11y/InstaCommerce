package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.LedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByPaymentId(UUID paymentId);

    // --- Wave 9: dedup guard + verification-job queries ---

    boolean existsByPaymentIdAndReferenceTypeAndReferenceId(
        UUID paymentId, String referenceType, String referenceId
    );

    @Query("""
        select le.referenceType   as referenceType,
               cast(le.entryType as string) as entryType,
               sum(le.amountCents) as totalAmountCents
          from LedgerEntry le
         where le.paymentId = :paymentId
         group by le.referenceType, le.entryType
        """)
    List<LedgerBalanceSummary> sumByPaymentIdGrouped(@Param("paymentId") UUID paymentId);

    @Query("select distinct le.paymentId from LedgerEntry le where le.createdAt >= :since")
    List<UUID> findDistinctPaymentIdsWithEntriesSince(
        @Param("since") Instant since, Pageable pageable
    );
}
