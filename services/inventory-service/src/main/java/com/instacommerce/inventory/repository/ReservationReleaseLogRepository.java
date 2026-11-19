package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.ReservationReleaseLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationReleaseLogRepository extends JpaRepository<ReservationReleaseLog, UUID> {
    boolean existsByReservationIdAndReleaseKey(UUID reservationId, String releaseKey);
}
