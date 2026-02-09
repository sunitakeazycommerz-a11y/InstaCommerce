package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {

    Optional<LoyaltyAccount> findByUserId(UUID userId);
}
