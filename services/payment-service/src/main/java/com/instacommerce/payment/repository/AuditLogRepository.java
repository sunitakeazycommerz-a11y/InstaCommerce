package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.AuditLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    void deleteByCreatedAtBefore(Instant cutoff);
}
