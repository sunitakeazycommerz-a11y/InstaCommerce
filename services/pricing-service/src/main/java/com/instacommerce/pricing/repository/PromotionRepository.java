package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.Promotion;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Promotion p WHERE p.id = :id")
    Optional<Promotion> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT p FROM Promotion p
        WHERE p.active = true
          AND (p.maxUses IS NULL OR p.currentUses < p.maxUses)
        """)
    List<Promotion> findActivePromotions();

    @Modifying
    @Query("""
        UPDATE Promotion p
        SET p.currentUses = p.currentUses + 1
        WHERE p.id = :id
          AND (p.maxUses IS NULL OR p.currentUses < p.maxUses)
        """)
    int incrementUsage(@Param("id") UUID id);

    @Modifying
    @Query("""
        UPDATE Promotion p
        SET p.currentUses = p.currentUses - 1
        WHERE p.id = :id AND p.currentUses > 0
        """)
    int decrementUsage(@Param("id") UUID id);
}
