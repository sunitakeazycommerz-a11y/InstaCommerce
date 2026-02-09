package com.instacommerce.identity.repository;

import com.instacommerce.identity.domain.model.AuditLog;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    void deleteByCreatedAtBefore(Instant cutoff);
}
