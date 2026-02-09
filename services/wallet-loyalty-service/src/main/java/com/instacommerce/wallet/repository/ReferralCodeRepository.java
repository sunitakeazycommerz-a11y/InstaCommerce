package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.ReferralCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReferralCode r WHERE r.code = :code")
    Optional<ReferralCode> findByCodeForUpdate(@Param("code") String code);

    Optional<ReferralCode> findByUserId(UUID userId);
}
