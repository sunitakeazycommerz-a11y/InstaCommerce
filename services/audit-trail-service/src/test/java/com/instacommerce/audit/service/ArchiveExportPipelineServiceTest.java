package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchiveExportPipelineServiceTest {

    private static final String BUCKET = "instacommerce-audit-archive-dev";
    private static final Instant START = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant END = Instant.parse("2025-03-01T00:00:00Z");

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private AuditArchiveManifestRepository manifestRepository;

    @Mock
    private Storage storage;

    @Mock
    private Blob uploadedBlob;

    private ArchiveExportPipelineService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new ArchiveExportPipelineService(
                auditEventRepository, manifestRepository, storage, objectMapper, 0L);
    }

    @Test
    void exportPartition_nonEmptyEvents_uploadsArchiveAndVerifiesManifest() throws Exception {
        AuditEvent e1 = sampleEvent(1L, "ORDER_CREATED", START.plusSeconds(60));
        AuditEvent e2 = sampleEvent(2L, "PAYMENT_CAPTURED", START.plusSeconds(120));
        when(auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(START, END))
                .thenReturn(List.of(e1, e2));
        when(uploadedBlob.getSize()).thenReturn(256L);
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
                .thenReturn(uploadedBlob);
        BlobId expectedBlobId = BlobId.of(BUCKET, "audit/partitions/2025/02/audit_events_2025_02.ndjson.gz");
        when(storage.get(expectedBlobId)).thenReturn(uploadedBlob);
        when(manifestRepository.save(any(AuditArchiveManifest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuditArchiveManifest manifest = service.exportPartition("audit_events_2025_02", START, END);

        assertThat(manifest.getVerificationStatus()).isEqualTo("VERIFIED");
        assertThat(manifest.getArchiveYear()).isEqualTo(2025);
        assertThat(manifest.getArchiveMonth()).isEqualTo(2);
        assertThat(manifest.getRowCount()).isEqualTo(2L);
        assertThat(manifest.getCompressedSizeBytes()).isPositive();
        assertThat(manifest.getChecksumSha256()).hasSize(64);
        assertThat(manifest.getCreationMethod()).isEqualTo("PARTITION_MAINTENANCE");
        assertThat(manifest.getS3GlacierPath())
                .isEqualTo("gs://" + BUCKET + "/audit/partitions/2025/02/audit_events_2025_02.ndjson.gz");

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storage).create(blobInfoCaptor.capture(), contentCaptor.capture(), any(Storage.BlobTargetOption[].class));
        assertThat(blobInfoCaptor.getValue().getBlobId()).isEqualTo(expectedBlobId);

        String decompressed;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(contentCaptor.getValue()))) {
            decompressed = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(decompressed.lines().toList()).hasSize(2);
        assertThat(decompressed).contains("ORDER_CREATED", "PAYMENT_CAPTURED");

        verify(storage).get(expectedBlobId);
    }

    @Test
    void exportPartition_emptyPartition_savesZeroRowVerifiedManifestWithoutUpload() {
        when(auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(START, END))
                .thenReturn(List.of());
        when(manifestRepository.save(any(AuditArchiveManifest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuditArchiveManifest manifest = service.exportPartition("audit_events_2025_02", START, END);

        assertThat(manifest.getVerificationStatus()).isEqualTo("VERIFIED");
        assertThat(manifest.getRowCount()).isZero();
        assertThat(manifest.getCompressedSizeBytes()).isZero();
        assertThat(manifest.getCreationMethod()).isEqualTo("PARTITION_MAINTENANCE");
        verify(storage, never()).create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
    }

    @Test
    void exportPartition_uploadFails_throwsAndDoesNotVerify() {
        AuditEvent e1 = sampleEvent(1L, "ORDER_CREATED", START.plusSeconds(60));
        when(auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(START, END))
                .thenReturn(List.of(e1));
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
                .thenThrow(new RuntimeException("GCS outage"));

        assertThatThrownBy(() -> service.exportPartition("audit_events_2025_02", START, END))
                .isInstanceOf(RuntimeException.class);

        verify(storage, times(3))
                .create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
        verify(manifestRepository, never()).save(any());
    }

    @Test
    void exportPartition_verificationBlobMissing_marksManifestFailedAndThrows() {
        AuditEvent e1 = sampleEvent(1L, "ORDER_CREATED", START.plusSeconds(60));
        when(auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(START, END))
                .thenReturn(List.of(e1));
        when(uploadedBlob.getSize()).thenReturn(128L);
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
                .thenReturn(uploadedBlob);
        BlobId expectedBlobId = BlobId.of(BUCKET, "audit/partitions/2025/02/audit_events_2025_02.ndjson.gz");
        when(storage.get(expectedBlobId)).thenReturn(null);
        when(manifestRepository.save(any(AuditArchiveManifest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.exportPartition("audit_events_2025_02", START, END))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<AuditArchiveManifest> savedCaptor = ArgumentCaptor.forClass(AuditArchiveManifest.class);
        verify(manifestRepository, atLeastOnce()).save(savedCaptor.capture());
        List<AuditArchiveManifest> saved = savedCaptor.getAllValues();
        assertThat(saved.get(saved.size() - 1).getVerificationStatus()).isEqualTo("FAILED");
    }

    private static AuditEvent sampleEvent(long seq, String eventType, Instant createdAt) {
        return AuditEvent.builder()
                .id(UUID.randomUUID())
                .eventType(eventType)
                .sourceService("order-service")
                .action("CREATE")
                .actorId(UUID.randomUUID())
                .resourceType("Order")
                .resourceId("ord-" + seq)
                .sequenceNumber(seq)
                .previousHash("prev-" + seq)
                .eventHash("hash-" + seq)
                .createdAt(createdAt)
                .build();
    }
}
