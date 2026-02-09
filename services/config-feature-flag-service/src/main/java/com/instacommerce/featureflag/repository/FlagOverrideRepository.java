package com.instacommerce.featureflag.repository;

import com.instacommerce.featureflag.domain.model.FlagOverride;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlagOverrideRepository extends JpaRepository<FlagOverride, UUID> {

    Optional<FlagOverride> findByFlagIdAndUserId(UUID flagId, UUID userId);

    @Query("SELECT o FROM FlagOverride o WHERE o.flagId = :flagId AND o.userId = :userId " +
           "AND (o.expiresAt IS NULL OR o.expiresAt > :now)")
    Optional<FlagOverride> findActiveByFlagIdAndUserId(@Param("flagId") UUID flagId,
                                                       @Param("userId") UUID userId,
                                                       @Param("now") Instant now);
}
