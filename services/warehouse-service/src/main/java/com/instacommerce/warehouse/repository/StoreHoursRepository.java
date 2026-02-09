package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.StoreHours;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreHoursRepository extends JpaRepository<StoreHours, UUID> {

    List<StoreHours> findByStoreId(UUID storeId);

    Optional<StoreHours> findByStoreIdAndDayOfWeek(UUID storeId, int dayOfWeek);
}
