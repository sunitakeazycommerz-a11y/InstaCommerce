package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly maintenance job that pre-creates future audit partitions and archives expired
 * partitions via {@link ArchiveExportPipelineService} before detaching them.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final Pattern PARTITION_NAME_PATTERN =
            Pattern.compile("^(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?(audit_events_(\\d{4})_(\\d{2}))$");

    private final JdbcTemplate jdbcTemplate;
    private final ArchiveExportPipelineService archiveExportPipelineService;
    private final int retentionDays;
    private final int futureMonths;
    private final Clock clock;

    @Autowired
    public PartitionMaintenanceJob(
            JdbcTemplate jdbcTemplate,
            ArchiveExportPipelineService archiveExportPipelineService,
            @Value("${audit.partition.retention-days:365}") int retentionDays,
            @Value("${audit.partition.future-months:3}") int futureMonths) {
        this(jdbcTemplate, archiveExportPipelineService, retentionDays, futureMonths, Clock.systemUTC());
    }

    PartitionMaintenanceJob(
            JdbcTemplate jdbcTemplate,
            ArchiveExportPipelineService archiveExportPipelineService,
            int retentionDays,
            int futureMonths,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.archiveExportPipelineService = archiveExportPipelineService;
        this.retentionDays = retentionDays;
        this.futureMonths = futureMonths;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 2 1 * *")
    @SchedulerLock(name = "partitionMaintenance", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    public void maintain() {
        createFuturePartitions();
        detachOldPartitions();
    }

    private void createFuturePartitions() {
        log.info("Creating future audit_events partitions for next {} months", futureMonths);
        jdbcTemplate.execute("SELECT ensure_future_audit_partitions(" + futureMonths + ")");
        log.info("Future partitions created successfully");
    }

    private void detachOldPartitions() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays).withDayOfMonth(1);
        log.info("Archiving and detaching audit_events partitions older than {}", cutoff);

        var partitions = jdbcTemplate.queryForList(
                "SELECT inhrelid::regclass::text AS partition_name " +
                "FROM pg_inherits " +
                "WHERE inhparent = 'audit_events'::regclass",
                String.class);

        for (String partitionName : partitions) {
            exportAndDetachIfExpired(partitionName, cutoff);
        }
    }

    private void exportAndDetachIfExpired(String partitionName, LocalDate cutoff) {
        if (partitionName == null) {
            return;
        }
        Matcher matcher = PARTITION_NAME_PATTERN.matcher(partitionName);
        if (!matcher.matches()) {
            log.warn("Skipping unrecognized partition name during detach: {}", partitionName);
            return;
        }

        String barePartitionName = matcher.group(1);
        LocalDate partitionDate;
        try {
            int year = Integer.parseInt(matcher.group(2));
            int month = Integer.parseInt(matcher.group(3));
            partitionDate = LocalDate.of(year, month, 1);
        } catch (DateTimeException | NumberFormatException e) {
            log.warn("Skipping partition {} with invalid date suffix: {}", partitionName, e.getMessage());
            return;
        }

        if (!partitionDate.isBefore(cutoff)) {
            return;
        }

        Instant rangeStart = partitionDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant rangeEnd = partitionDate.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        try {
            AuditArchiveManifest manifest = archiveExportPipelineService.exportPartition(
                    barePartitionName, rangeStart, rangeEnd);
            if (manifest == null || !"VERIFIED".equals(manifest.getVerificationStatus())) {
                String status = manifest != null ? manifest.getVerificationStatus() : "null";
                log.error("Archive verification incomplete for partition {} (status={}), deferring detach",
                        partitionName, status);
                return;
            }

            log.info("Detaching archived partition: {}", partitionName);
            jdbcTemplate.execute("ALTER TABLE audit_events DETACH PARTITION " + partitionName);
            log.info("Detached partition {} after verified archive export", partitionName);
        } catch (Exception e) {
            log.error("Archive export failed for partition {}, deferring detach: {}",
                    partitionName, e.getMessage(), e);
        }
    }
}
