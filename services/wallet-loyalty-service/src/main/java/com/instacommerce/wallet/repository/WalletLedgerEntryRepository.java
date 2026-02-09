package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.WalletLedgerEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    @Query("SELECT COALESCE(SUM(CASE WHEN e.creditAccount = :account THEN e.amountCents ELSE 0 END), 0) - "
         + "COALESCE(SUM(CASE WHEN e.debitAccount = :account THEN e.amountCents ELSE 0 END), 0) "
         + "FROM WalletLedgerEntry e WHERE e.debitAccount = :account OR e.creditAccount = :account")
    long computeBalanceForAccount(@Param("account") String account);
}
