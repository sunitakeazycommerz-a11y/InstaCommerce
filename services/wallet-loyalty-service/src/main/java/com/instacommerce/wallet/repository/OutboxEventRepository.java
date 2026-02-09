package com.instacommerce.wallet.repository;

import com.instacommerce.wallet.domain.model.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.sent = true AND e.createdAt < :cutoff")
    int deleteSentEventsBefore(@Param("cutoff") Instant cutoff);
}
