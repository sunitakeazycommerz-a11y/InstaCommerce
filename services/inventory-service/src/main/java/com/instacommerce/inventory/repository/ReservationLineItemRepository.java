package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.ReservationLineItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationLineItemRepository extends JpaRepository<ReservationLineItem, UUID> {
    List<ReservationLineItem> findByReservation_Id(UUID reservationId);
}
