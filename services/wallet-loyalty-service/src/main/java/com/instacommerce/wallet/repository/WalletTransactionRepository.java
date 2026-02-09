package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.WalletTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    Optional<WalletTransaction> findByReferenceTypeAndReferenceId(
            WalletTransaction.ReferenceType referenceType, String referenceId);
}
