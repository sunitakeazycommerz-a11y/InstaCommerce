package com.instacommerce.identity.repository;

import com.instacommerce.identity.domain.model.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUser_IdAndRevokedFalseOrderByCreatedAtAsc(UUID userId);

    void deleteAllByUser_Id(UUID userId);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.revoked = false")
    int revokeAllActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE (rt.revoked = true OR rt.expiresAt < CURRENT_TIMESTAMP) AND rt.createdAt < :cutoff")
    void deleteExpiredAndRevokedBefore(@Param("cutoff") Instant cutoff);
}
