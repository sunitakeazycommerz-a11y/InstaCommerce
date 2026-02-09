package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.ReferralCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByCode(String code);

    Optional<ReferralCode> findByUserId(UUID userId);
}
