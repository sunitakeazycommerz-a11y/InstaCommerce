package com.instacommerce.notification.repository;

import com.instacommerce.notification.domain.model.DeviceToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {
    Optional<DeviceToken> findByToken(String token);

    Optional<DeviceToken> findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(UUID userId);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
