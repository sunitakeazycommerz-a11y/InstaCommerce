package com.instacommerce.audit.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;
    private final int futureMonths;

    public PartitionMaintenanceJob(JdbcTemplate jdbcTemplate,
                                   @Value("${audit.partition.retention-days:90}") int retentionDays,
                                   @Value("${audit.partition.future-months:3}") int futureMonths) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
        this.futureMonths = futureMonths;
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
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays).withDayOfMonth(1);
        log.info("Detaching audit_events partitions older than {}", cutoff);

        var partitions = jdbcTemplate.queryForList(
                "SELECT inhrelid::regclass::text AS partition_name " +
                "FROM pg_inherits " +
                "WHERE inhparent = 'audit_events'::regclass",
                String.class);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy_MM");

        for (String partitionName : partitions) {
            // Partition names follow pattern: audit_events_YYYY_MM
            String suffix = partitionName.replace("audit_events_", "");
            try {
                LocalDate partitionDate = LocalDate.parse(suffix + "_01",
                        DateTimeFormatter.ofPattern("yyyy_MM_dd"));
                if (partitionDate.isBefore(cutoff)) {
                    log.info("Detaching old partition: {}", partitionName);
                    jdbcTemplate.execute(
                            "ALTER TABLE audit_events DETACH PARTITION " + partitionName);
                    log.info("Detached partition {} for archival", partitionName);
                }
            } catch (Exception e) {
                log.warn("Skipping partition {} during detach: {}", partitionName, e.getMessage());
            }
        }
    }
}
