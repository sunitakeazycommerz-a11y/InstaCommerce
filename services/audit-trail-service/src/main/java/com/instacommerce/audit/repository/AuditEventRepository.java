package com.instacommerce.audit.repository;

import com.instacommerce.audit.domain.model.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>,
        JpaSpecificationExecutor<AuditEvent> {

    Page<AuditEvent> findByActorId(UUID actorId, Pageable pageable);

    Page<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId, Pageable pageable);

    @Query("SELECT a FROM AuditEvent a WHERE a.sourceService = :sourceService " +
           "AND a.createdAt >= :from AND a.createdAt < :to ORDER BY a.createdAt DESC")
    Page<AuditEvent> findBySourceServiceAndCreatedAtBetween(
            @Param("sourceService") String sourceService,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    Page<AuditEvent> findByEventType(String eventType, Pageable pageable);

    @Query("SELECT a FROM AuditEvent a WHERE a.sequenceNumber IS NOT NULL " +
           "AND (:from IS NULL OR a.createdAt >= :from) " +
           "AND (:to IS NULL OR a.createdAt < :to) ORDER BY a.sequenceNumber ASC")
    List<AuditEvent> findChainedEvents(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
