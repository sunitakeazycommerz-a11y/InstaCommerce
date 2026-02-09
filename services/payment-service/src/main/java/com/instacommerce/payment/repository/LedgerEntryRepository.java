package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByPaymentId(UUID paymentId);
}
