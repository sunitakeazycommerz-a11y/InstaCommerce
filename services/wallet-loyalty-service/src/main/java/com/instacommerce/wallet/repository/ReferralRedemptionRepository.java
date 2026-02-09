package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.ReferralRedemption;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralRedemptionRepository extends JpaRepository<ReferralRedemption, UUID> {

    Optional<ReferralRedemption> findByReferredUserId(UUID referredUserId);
}
