package com.instacommerce.catalog.service;

import com.instacommerce.catalog.repository.AuditLogRepository;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditLogCleanupJob {
    private final AuditLogRepository auditLogRepository;
    private final Duration retention;

    public AuditLogCleanupJob(AuditLogRepository auditLogRepository,
                              @Value("${audit-log.retention:730d}") Duration retention) {
        this.auditLogRepository = auditLogRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${audit-log.cleanup-cron:0 0 3 * * *}")
    @SchedulerLock(name = "auditLogCleanup", lockAtLeastFor = "5m", lockAtMostFor = "30m")
    @Transactional
    public void purgeExpiredLogs() {
        Instant cutoff = Instant.now().minus(retention);
        auditLogRepository.deleteByCreatedAtBefore(cutoff);
    }
}
