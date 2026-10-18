package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.StagingTemperatureTelemetry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagingTelemetryRepository extends JpaRepository<StagingTemperatureTelemetry, UUID> {

    List<StagingTemperatureTelemetry> findTop10ByStoreIdAndBayIdOrderByRecordedAtDesc(UUID storeId, String bayId);

    Optional<StagingTemperatureTelemetry> findTop1ByStoreIdAndBayIdOrderByRecordedAtDesc(UUID storeId, String bayId);

    long countByStoreIdAndBayIdAndBreachedTrueAndRecordedAtAfter(UUID storeId, String bayId, Instant after);

    List<StagingTemperatureTelemetry> findByStoreIdOrderByRecordedAtDesc(UUID storeId);
}
