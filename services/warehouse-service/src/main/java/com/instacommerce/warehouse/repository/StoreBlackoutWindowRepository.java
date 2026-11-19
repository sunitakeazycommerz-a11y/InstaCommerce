package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.StoreBlackoutWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreBlackoutWindowRepository extends JpaRepository<StoreBlackoutWindow, UUID> {

    List<StoreBlackoutWindow> findByStoreIdOrderByStartsAtAsc(UUID storeId);

    Optional<StoreBlackoutWindow> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("""
            SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END
            FROM StoreBlackoutWindow w
            WHERE w.store.id = :storeId
              AND w.startsAt <= :at
              AND w.endsAt > :at
            """)
    boolean existsActiveBlackout(@Param("storeId") UUID storeId, @Param("at") Instant at);
}
