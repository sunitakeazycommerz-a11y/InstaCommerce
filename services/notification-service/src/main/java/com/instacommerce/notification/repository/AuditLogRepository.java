package com.instacommerce.notification.repository;

import com.instacommerce.notification.domain.model.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
