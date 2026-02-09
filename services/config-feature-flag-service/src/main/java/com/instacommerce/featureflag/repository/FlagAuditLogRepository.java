package com.instacommerce.featureflag.repository;

import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlagAuditLogRepository extends JpaRepository<FlagAuditLog, UUID> {

    List<FlagAuditLog> findByFlagIdOrderByChangedAtDesc(UUID flagId);
}
