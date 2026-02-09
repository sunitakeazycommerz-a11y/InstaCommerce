package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.createdAt < :cutoff AND e.sent = true")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
