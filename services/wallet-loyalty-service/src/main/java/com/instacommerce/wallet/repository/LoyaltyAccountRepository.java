package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {

    Optional<LoyaltyAccount> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT la FROM LoyaltyAccount la WHERE la.userId = :userId")
    Optional<LoyaltyAccount> findByUserIdForUpdate(@Param("userId") UUID userId);

    Slice<LoyaltyAccount> findAllBy(Pageable pageable);
}
