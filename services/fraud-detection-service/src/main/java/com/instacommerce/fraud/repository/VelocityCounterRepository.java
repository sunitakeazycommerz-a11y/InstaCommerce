package com.instacommerce.fraud.repository;

import com.instacommerce.fraud.domain.model.VelocityCounter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VelocityCounterRepository extends JpaRepository<VelocityCounter, UUID> {

    @Query("SELECT v FROM VelocityCounter v WHERE v.entityType = :entityType " +
           "AND v.entityId = :entityId AND v.counterType = :counterType " +
           "AND v.windowStart <= :now AND v.windowEnd > :now")
    Optional<VelocityCounter> findByEntityTypeAndEntityIdAndCounterTypeAndWindowContaining(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("counterType") String counterType,
            @Param("now") Instant now);

    @Modifying
    @Query(value = "INSERT INTO velocity_counters (entity_type, entity_id, counter_type, counter_value, window_start, window_end) " +
                   "VALUES (:entityType, :entityId, :counterType, :counterValue, :windowStart, :windowEnd) " +
                   "ON CONFLICT ON CONSTRAINT uq_velocity_window " +
                   "DO UPDATE SET counter_value = velocity_counters.counter_value + :counterValue",
            nativeQuery = true)
    void upsertCounter(@Param("entityType") String entityType,
                       @Param("entityId") String entityId,
                       @Param("counterType") String counterType,
                       @Param("counterValue") long counterValue,
                       @Param("windowStart") Instant windowStart,
                       @Param("windowEnd") Instant windowEnd);

    int deleteByWindowEndBefore(Instant cutoff);
}
