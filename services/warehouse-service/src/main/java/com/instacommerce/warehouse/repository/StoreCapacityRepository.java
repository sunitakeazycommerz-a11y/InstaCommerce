package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.StoreCapacity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreCapacityRepository extends JpaRepository<StoreCapacity, UUID> {

    Optional<StoreCapacity> findByStoreIdAndDateAndHour(UUID storeId, LocalDate date, int hour);

    @Modifying
    @Query("UPDATE StoreCapacity sc SET sc.currentOrders = sc.currentOrders + 1, sc.updatedAt = CURRENT_TIMESTAMP WHERE sc.id = :id AND sc.currentOrders < sc.maxOrders")
    int incrementOrderCount(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM StoreCapacity sc WHERE sc.date < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDate cutoffDate);
}
