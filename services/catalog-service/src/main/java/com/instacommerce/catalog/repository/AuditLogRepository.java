package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.AuditLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    void deleteByCreatedAtBefore(Instant cutoff);
}
