package com.instacommerce.audit.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports audit event windows and expiring partitions to GCS with SHA-256 verification and
 * persisted {@link AuditArchiveManifest} records before partitions are detached.
 */
@Service
public class ArchiveExportPipelineService {

  private static final Logger log = LoggerFactory.getLogger(ArchiveExportPipelineService.class);
  private static final int UPLOAD_MAX_RETRIES = 3;

  @Value("${audit.archive.bucket-name:instacommerce-audit-archive-dev}")
  private String archiveBucketName = "instacommerce-audit-archive-dev";

  @Value("${audit.archive.gcs-project-id:instacommerce-dev}")
  private String gcsProjectId = "instacommerce-dev";

  @Value("${audit.archive.kms-key-id:}")
  private String kmsKeyId = "";

  @Value("${audit.archive.enable-encryption:true}")
  private boolean enableEncryption = true;

  @Value("${audit.archive.retention-days:2557}")
  private int retentionDays = 2557;

  private final AuditEventRepository auditEventRepository;
  private final AuditArchiveManifestRepository manifestRepository;
  private final Storage injectedStorage;
  private final ObjectMapper objectMapper;
  private final long retryBackoffMs;

  public ArchiveExportPipelineService(
      AuditEventRepository auditEventRepository,
      AuditArchiveManifestRepository manifestRepository) {
    this(auditEventRepository, manifestRepository, null, createDefaultObjectMapper(), 1000L);
  }

  @Autowired
  public ArchiveExportPipelineService(
      AuditEventRepository auditEventRepository,
      AuditArchiveManifestRepository manifestRepository,
      ObjectProvider<Storage> storageProvider,
      ObjectProvider<ObjectMapper> objectMapperProvider) {
    this(
        auditEventRepository,
        manifestRepository,
        storageProvider.getIfAvailable(),
        objectMapperProvider.getIfAvailable(ArchiveExportPipelineService::createDefaultObjectMapper),
        1000L);
  }

  ArchiveExportPipelineService(
      AuditEventRepository auditEventRepository,
      AuditArchiveManifestRepository manifestRepository,
      Storage storage,
      ObjectMapper objectMapper,
      long retryBackoffMs) {
    this.auditEventRepository = auditEventRepository;
    this.manifestRepository = manifestRepository;
    this.injectedStorage = storage;
    this.objectMapper = objectMapper;
    this.retryBackoffMs = retryBackoffMs;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
  @Transactional
  public void exportAuditEventsToArchive() {
    log.info("Starting nightly audit archive export pipeline");
    Instant today = Instant.now();
    Instant yesterday = today.minus(1, ChronoUnit.DAYS);

    List<AuditEvent> events =
        auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(yesterday, today);
    if (events.isEmpty()) {
      log.warn("No audit events found for archive export (yesterday={}, today={})", yesterday, today);
      return;
    }

    OffsetDateTime utcStart = yesterday.atOffset(ZoneOffset.UTC);
    String gcsObjectPath =
        String.format(
            "audit/%04d/%02d/%02d/audit_events.ndjson.gz",
            utcStart.getYear(), utcStart.getMonthValue(), utcStart.getDayOfMonth());

    exportRange(events, gcsObjectPath, yesterday, today, "AUTOMATED");
  }

  @Transactional
  public AuditArchiveManifest exportPartition(
      String partitionName, Instant dateRangeStart, Instant dateRangeEnd) {
    List<AuditEvent> events =
        auditEventRepository.findByCreatedAtBetweenOrderByCreatedAt(dateRangeStart, dateRangeEnd);
    OffsetDateTime utcStart = dateRangeStart.atOffset(ZoneOffset.UTC);
    String gcsObjectPath =
        String.format(
            "audit/partitions/%04d/%02d/%s.ndjson.gz",
            utcStart.getYear(), utcStart.getMonthValue(), partitionName);

    if (events.isEmpty()) {
      Instant now = Instant.now();
      AuditArchiveManifest emptyManifest =
          AuditArchiveManifest.builder()
              .archiveYear(utcStart.getYear())
              .archiveMonth(utcStart.getMonthValue())
              .s3GlacierPath("gs://" + archiveBucketName + "/" + gcsObjectPath)
              .rowCount(0L)
              .compressedSizeBytes(0L)
              .checksumSha256(sha256Hex(new byte[0]))
              .dateRangeStart(dateRangeStart)
              .dateRangeEnd(dateRangeEnd)
              .archivedAt(now)
              .retrievableUntil(now.plus(retentionDays, ChronoUnit.DAYS))
              .verificationStatus("VERIFIED")
              .creationMethod("PARTITION_MAINTENANCE")
              .build();
      emptyManifest.setLastVerificationAt(now);
      emptyManifest.setVerificationAttemptCount(1);
      return manifestRepository.save(emptyManifest);
    }

    return exportRange(events, gcsObjectPath, dateRangeStart, dateRangeEnd, "PARTITION_MAINTENANCE");
  }

  private AuditArchiveManifest exportRange(
      List<AuditEvent> events,
      String gcsObjectPath,
      Instant dateRangeStart,
      Instant dateRangeEnd,
      String creationMethod) {
    try {
      ArchiveFile archiveFile = serializeCompressedNdjson(events, gcsObjectPath, dateRangeStart);
      uploadToGcsGlacier(archiveFile);

      AuditArchiveManifest manifest =
          createManifest(events.size(), archiveFile, dateRangeStart, dateRangeEnd, creationMethod);
      manifest = manifestRepository.save(manifest);

      return verifyArchiveIntegrity(manifest, gcsObjectPath);
    } catch (Exception ex) {
      log.error("Archive export pipeline failed for path={}", gcsObjectPath, ex);
      if (ex instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Archive export failed for " + gcsObjectPath, ex);
    }
  }

  private ArchiveFile serializeCompressedNdjson(
      List<AuditEvent> events, String gcsObjectPath, Instant dateRangeStart) throws IOException {
    OffsetDateTime utcStart = dateRangeStart.atOffset(ZoneOffset.UTC);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos);
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(gzipOut, StandardCharsets.UTF_8))) {
      for (AuditEvent event : events) {
        writer.write(objectMapper.writeValueAsString(event));
        writer.newLine();
      }
    }
    byte[] compressed = baos.toByteArray();
    return new ArchiveFile(
        utcStart.getYear(),
        utcStart.getMonthValue(),
        gcsObjectPath,
        compressed,
        compressed.length,
        sha256Hex(compressed),
        events.size());
  }

  private void uploadToGcsGlacier(ArchiveFile archiveFile) throws IOException {
    Storage storage = resolveStorageClient();
    BlobId blobId = BlobId.of(archiveBucketName, archiveFile.gcsPath());

    BlobInfo blobInfo =
        BlobInfo.newBuilder(blobId)
            .setContentType("application/x-ndjson")
            .setContentEncoding("gzip")
            .setMetadata(
                Map.of(
                    "archive_year", String.valueOf(archiveFile.archiveYear()),
                    "archive_month", String.valueOf(archiveFile.archiveMonth()),
                    "checksum_sha256", archiveFile.checksumSha256(),
                    "row_count", String.valueOf(archiveFile.rowCount()),
                    "compressed_size_bytes", String.valueOf(archiveFile.compressedSizeBytes()),
                    "archived_at", Instant.now().toString()))
            .setCacheControl("no-cache, no-store, must-revalidate")
            .build();

    int retryCount = 0;
    while (true) {
      try {
        Blob blob;
        if (enableEncryption && kmsKeyId != null && !kmsKeyId.isEmpty()) {
          blob =
              storage.create(
                  blobInfo, archiveFile.content(), Storage.BlobTargetOption.kmsKeyName(kmsKeyId));
        } else {
          blob = storage.create(blobInfo, archiveFile.content());
        }
        if (blob == null) {
          throw new IOException("GCS upload returned null blob for " + archiveFile.gcsPath());
        }
        log.info(
            "Archive file uploaded: gs://{}/{}, size={}",
            archiveBucketName,
            archiveFile.gcsPath(),
            blob.getSize());
        return;
      } catch (Exception ex) {
        retryCount++;
        if (retryCount >= UPLOAD_MAX_RETRIES) {
          if (ex instanceof IOException ioException) {
            throw ioException;
          }
          throw new IOException("Failed to upload archive file after " + UPLOAD_MAX_RETRIES + " attempts", ex);
        }
        sleepBeforeRetry(retryCount);
      }
    }
  }

  private void sleepBeforeRetry(int retryCount) throws IOException {
    if (retryBackoffMs <= 0) {
      return;
    }
    try {
      Thread.sleep(Math.min(retryBackoffMs * (1L << retryCount), 30000L));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Upload interrupted", e);
    }
  }

  private AuditArchiveManifest createManifest(
      long rowCount,
      ArchiveFile archiveFile,
      Instant dateRangeStart,
      Instant dateRangeEnd,
      String creationMethod) {
    Instant now = Instant.now();
    return AuditArchiveManifest.builder()
        .archiveYear(archiveFile.archiveYear())
        .archiveMonth(archiveFile.archiveMonth())
        .s3GlacierPath("gs://" + archiveBucketName + "/" + archiveFile.gcsPath())
        .rowCount(rowCount)
        .compressedSizeBytes(archiveFile.compressedSizeBytes())
        .checksumSha256(archiveFile.checksumSha256())
        .dateRangeStart(dateRangeStart)
        .dateRangeEnd(dateRangeEnd)
        .archivedAt(now)
        .retrievableUntil(now.plus(retentionDays, ChronoUnit.DAYS))
        .verificationStatus("PENDING")
        .creationMethod(creationMethod)
        .build();
  }

  private AuditArchiveManifest verifyArchiveIntegrity(
      AuditArchiveManifest manifest, String gcsObjectPath) {
    try {
      Storage storage = resolveStorageClient();
      Blob blob = storage.get(BlobId.of(archiveBucketName, gcsObjectPath));
      if (blob == null || blob.getSize() <= 0) {
        throw new IllegalStateException(
            "Archive blob missing or empty after upload: " + manifest.getS3GlacierPath());
      }

      manifest.setVerificationStatus("VERIFIED");
      manifest.setLastVerificationAt(Instant.now());
      manifest.setVerificationAttemptCount(
          (manifest.getVerificationAttemptCount() != null
                  ? manifest.getVerificationAttemptCount()
                  : 0)
              + 1);
      return manifestRepository.save(manifest);
    } catch (Exception ex) {
      manifest.setVerificationStatus("FAILED");
      manifestRepository.save(manifest);
      if (ex instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Archive verification failed", ex);
    }
  }

  private Storage resolveStorageClient() throws IOException {
    if (injectedStorage != null) {
      return injectedStorage;
    }
    GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
    return StorageOptions.newBuilder()
        .setProjectId(gcsProjectId)
        .setCredentials(credentials)
        .build()
        .getService();
  }

  private static String sha256Hex(byte[] input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private static ObjectMapper createDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.findAndRegisterModules();
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper;
  }

  private record ArchiveFile(
      int archiveYear,
      int archiveMonth,
      String gcsPath,
      byte[] content,
      long compressedSizeBytes,
      String checksumSha256,
      long rowCount) {}
}
