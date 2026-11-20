package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.WalletTransaction;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    Optional<WalletTransaction> findByReferenceTypeAndReferenceId(
            WalletTransaction.ReferenceType referenceType, String referenceId);

    @Query("SELECT COALESCE(SUM(t.amountCents), 0) FROM WalletTransaction t "
            + "WHERE t.wallet.userId = :userId AND t.type = 'CREDIT' "
            + "AND t.referenceType = :referenceType AND t.createdAt >= :since")
    long sumCreditsByUserIdAndReferenceTypeSince(
            @Param("userId") UUID userId,
            @Param("referenceType") WalletTransaction.ReferenceType referenceType,
            @Param("since") Instant since);
}
