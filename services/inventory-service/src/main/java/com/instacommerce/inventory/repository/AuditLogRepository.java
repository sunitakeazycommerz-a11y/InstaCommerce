package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.AuditLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    @Modifying
    @Query(value = "DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log WHERE created_at < :cutoff LIMIT :batchSize)",
        nativeQuery = true)
    int deleteBatchByCreatedAtBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
