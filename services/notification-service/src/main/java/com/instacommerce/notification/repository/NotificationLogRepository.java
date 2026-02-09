package com.instacommerce.notification.repository;

import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    Optional<NotificationLog> findByEventIdAndChannel(String eventId, NotificationChannel channel);

    @Modifying
    @Query("update NotificationLog log set log.recipient = :recipient where log.userId = :userId")
    int anonymizeByUserId(@Param("userId") UUID userId, @Param("recipient") String recipient);

    List<NotificationLog> findByStatusAndNextRetryAtLessThanEqual(NotificationStatus status, Instant now);

    @Modifying
    @Query("DELETE FROM NotificationLog log WHERE log.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
