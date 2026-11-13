package com.instacommerce.audit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceJobTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-03-15T02:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ArchiveExportPipelineService archiveExportPipelineService;

    private PartitionMaintenanceJob job;

    @BeforeEach
    void setUp() {
        job = new PartitionMaintenanceJob(
                jdbcTemplate, archiveExportPipelineService, 365, 3, FIXED_CLOCK);
    }

    @Test
    void maintain_exportsPartitionAndVerifiesBeforeDetaching() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("audit_events_2025_02", "audit_events_2025_03"));
        AuditArchiveManifest verifiedManifest = AuditArchiveManifest.builder()
                .verificationStatus("VERIFIED")
                .build();
        Instant febStart = Instant.parse("2025-02-01T00:00:00Z");
        Instant marStart = Instant.parse("2025-03-01T00:00:00Z");
        when(archiveExportPipelineService.exportPartition("audit_events_2025_02", febStart, marStart))
                .thenReturn(verifiedManifest);

        job.maintain();

        InOrder inOrder = inOrder(jdbcTemplate, archiveExportPipelineService);
        inOrder.verify(jdbcTemplate).execute("SELECT ensure_future_audit_partitions(3)");
        inOrder.verify(archiveExportPipelineService)
                .exportPartition("audit_events_2025_02", febStart, marStart);
        inOrder.verify(jdbcTemplate).execute("ALTER TABLE audit_events DETACH PARTITION audit_events_2025_02");

        verify(archiveExportPipelineService, never())
                .exportPartition(eq("audit_events_2025_03"), any(), any());
        verify(jdbcTemplate, never())
                .execute("ALTER TABLE audit_events DETACH PARTITION audit_events_2025_03");
    }

    @Test
    void maintain_exportThrows_abortsDetachForFailedPartitionOnly() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("audit_events_2025_01", "audit_events_2025_02"));
        Instant janStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant febStart = Instant.parse("2025-02-01T00:00:00Z");
        Instant marStart = Instant.parse("2025-03-01T00:00:00Z");

        when(archiveExportPipelineService.exportPartition("audit_events_2025_01", janStart, febStart))
                .thenThrow(new RuntimeException("GCS upload failed"));
        when(archiveExportPipelineService.exportPartition("audit_events_2025_02", febStart, marStart))
                .thenReturn(AuditArchiveManifest.builder().verificationStatus("VERIFIED").build());

        job.maintain();

        verify(jdbcTemplate, never())
                .execute("ALTER TABLE audit_events DETACH PARTITION audit_events_2025_01");
        verify(jdbcTemplate).execute("ALTER TABLE audit_events DETACH PARTITION audit_events_2025_02");
    }

    @Test
    void maintain_manifestNotVerifiedOrNull_defersDetach() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("audit_events_2024_12", "audit_events_2025_01"));
        Instant decStart = Instant.parse("2024-12-01T00:00:00Z");
        Instant janStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant febStart = Instant.parse("2025-02-01T00:00:00Z");

        when(archiveExportPipelineService.exportPartition("audit_events_2024_12", decStart, janStart))
                .thenReturn(AuditArchiveManifest.builder().verificationStatus("FAILED").build());
        when(archiveExportPipelineService.exportPartition("audit_events_2025_01", janStart, febStart))
                .thenReturn(null);

        job.maintain();

        verify(jdbcTemplate, never()).execute(contains("DETACH PARTITION"));
    }

    @Test
    void maintain_schemaQualifiedPartition_exportsBareNameAndDetachesQualified() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("public.audit_events_2025_01"));
        Instant janStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant febStart = Instant.parse("2025-02-01T00:00:00Z");
        when(archiveExportPipelineService.exportPartition("audit_events_2025_01", janStart, febStart))
                .thenReturn(AuditArchiveManifest.builder().verificationStatus("VERIFIED").build());

        job.maintain();

        InOrder inOrder = inOrder(jdbcTemplate, archiveExportPipelineService);
        inOrder.verify(archiveExportPipelineService)
                .exportPartition("audit_events_2025_01", janStart, febStart);
        inOrder.verify(jdbcTemplate)
                .execute("ALTER TABLE audit_events DETACH PARTITION public.audit_events_2025_01");
    }

    @Test
    void maintain_ignoresMalformedOrDefaultPartitions() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("audit_events_2025_01; DROP TABLE audit_events", "audit_events_default"));

        job.maintain();

        verifyNoInteractions(archiveExportPipelineService);
        verify(jdbcTemplate, never()).execute(contains("DETACH PARTITION"));
    }
}
