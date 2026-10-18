package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.WarehouseBin;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseBinRepository extends JpaRepository<WarehouseBin, UUID> {

    Optional<WarehouseBin> findByStoreIdAndAisleAndRackAndShelfAndBinNumber(
            UUID storeId, String aisle, String rack, String shelf, String binNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM WarehouseBin b WHERE b.store.id = :storeId AND b.aisle = :aisle AND b.rack = :rack AND b.shelf = :shelf AND b.binNumber = :binNumber")
    Optional<WarehouseBin> findWithLockByStoreIdAndAisleAndRackAndShelfAndBinNumber(
            @Param("storeId") UUID storeId,
            @Param("aisle") String aisle,
            @Param("rack") String rack,
            @Param("shelf") String shelf,
            @Param("binNumber") String binNumber);

    List<WarehouseBin> findByStoreId(UUID storeId);

    List<WarehouseBin> findByStoreIdAndSku(UUID storeId, String sku);
}
