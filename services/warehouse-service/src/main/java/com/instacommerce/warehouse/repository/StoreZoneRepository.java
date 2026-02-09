package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.StoreZone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreZoneRepository extends JpaRepository<StoreZone, UUID> {

    List<StoreZone> findByPincode(String pincode);

    List<StoreZone> findByStoreId(UUID storeId);

    @Query("SELECT sz FROM StoreZone sz JOIN FETCH sz.store WHERE sz.pincode = :pincode AND sz.store.status = 'ACTIVE'")
    List<StoreZone> findActiveZonesByPincode(@Param("pincode") String pincode);
}
