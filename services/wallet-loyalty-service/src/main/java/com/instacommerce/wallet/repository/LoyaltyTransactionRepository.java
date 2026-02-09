package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {

    @Query("SELECT COALESCE(SUM(t.points), 0) FROM LoyaltyTransaction t " +
           "WHERE t.account.id = :accountId AND t.type = 'EARN' AND t.createdAt < :cutoff " +
           "AND NOT EXISTS (SELECT 1 FROM LoyaltyTransaction e WHERE e.account.id = :accountId " +
           "AND e.type = 'EXPIRE' AND e.referenceId = CAST(t.id AS string))")
    int sumExpirablePoints(@Param("accountId") UUID accountId, @Param("cutoff") Instant cutoff);

    @Query("SELECT t FROM LoyaltyTransaction t " +
           "WHERE t.account.id = :accountId AND t.type = 'EARN' AND t.createdAt < :cutoff " +
           "AND NOT EXISTS (SELECT 1 FROM LoyaltyTransaction e WHERE e.account.id = :accountId " +
           "AND e.type = 'EXPIRE' AND e.referenceId = CAST(t.id AS string)) " +
           "ORDER BY t.createdAt")
    List<LoyaltyTransaction> findExpirableEarnTransactions(@Param("accountId") UUID accountId,
                                                           @Param("cutoff") Instant cutoff,
                                                           Pageable pageable);
}
