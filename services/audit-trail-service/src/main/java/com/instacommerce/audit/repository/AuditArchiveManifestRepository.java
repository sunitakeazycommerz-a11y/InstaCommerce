package com.instacommerce.audit.repository;

import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditArchiveManifestRepository extends JpaRepository<AuditArchiveManifest, UUID> {

    boolean existsByVerificationStatus(String verificationStatus);

    List<AuditArchiveManifest> findByVerificationStatusOrderByDateRangeStartAsc(String verificationStatus);
}
