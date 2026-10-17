package com.instacommerce.audit.repository;

import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditArchiveManifestRepository extends JpaRepository<AuditArchiveManifest, UUID> {
}

