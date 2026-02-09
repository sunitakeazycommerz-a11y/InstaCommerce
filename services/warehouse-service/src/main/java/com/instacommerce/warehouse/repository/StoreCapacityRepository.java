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
    @Query(value = """
            INSERT INTO store_capacity (store_id, date, hour, current_orders, max_orders, created_at, updated_at)
            VALUES (:storeId, :date, :hour, 1, :maxOrders, now(), now())
            ON CONFLICT (store_id, date, hour)
            DO UPDATE SET current_orders = store_capacity.current_orders + 1, updated_at = now()
            WHERE store_capacity.current_orders < store_capacity.max_orders
            """, nativeQuery = true)
    int incrementOrderCount(@Param("storeId") UUID storeId,
                            @Param("date") LocalDate date,
                            @Param("hour") int hour,
                            @Param("maxOrders") int maxOrders);

    @Modifying
    @Query("DELETE FROM StoreCapacity sc WHERE sc.date < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDate cutoffDate);
}
