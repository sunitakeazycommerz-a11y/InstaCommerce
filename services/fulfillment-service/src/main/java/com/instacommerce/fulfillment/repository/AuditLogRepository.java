package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
