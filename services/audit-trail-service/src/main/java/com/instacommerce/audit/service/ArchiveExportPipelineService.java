package com.instacommerce.audit.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
  private static final int EXPORT_BATCH_SIZE = 1000;
  private static final int STREAM_BUFFER_SIZE = 8192;

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
  @SchedulerLock(name = "auditArchiveExport", lockAtLeastFor = "PT5M", lockAtMostFor = "PT1H")
  @Transactional
  public void exportAuditEventsToArchive() {
    log.info("Starting nightly audit archive export pipeline");
    Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    Instant yesterday = today.minus(1, ChronoUnit.DAYS);

    OffsetDateTime utcStart = yesterday.atOffset(ZoneOffset.UTC);
    String gcsObjectPath =
        String.format(
            "audit/%04d/%02d/%02d/audit_events.ndjson.gz",
            utcStart.getYear(), utcStart.getMonthValue(), utcStart.getDayOfMonth());

    exportRange(gcsObjectPath, yesterday, today, "AUTOMATED", false);
  }

  @Transactional
  public AuditArchiveManifest exportPartition(
      String partitionName, Instant dateRangeStart, Instant dateRangeEnd) {
    OffsetDateTime utcStart = dateRangeStart.atOffset(ZoneOffset.UTC);
    String gcsObjectPath =
        String.format(
            "audit/partitions/%04d/%02d/%s.ndjson.gz",
            utcStart.getYear(), utcStart.getMonthValue(), partitionName);

    return exportRange(gcsObjectPath, dateRangeStart, dateRangeEnd, "PARTITION_MAINTENANCE", true);
  }

  private AuditArchiveManifest exportRange(
      String gcsObjectPath,
      Instant dateRangeStart,
      Instant dateRangeEnd,
      String creationMethod,
      boolean persistEmptyManifest) {
    Path tempFile = null;
    try {
      ArchiveFile archiveFile = serializeCompressedNdjson(gcsObjectPath, dateRangeStart, dateRangeEnd);
      tempFile = archiveFile.tempFile();

      if (archiveFile.rowCount() == 0L) {
        if (!persistEmptyManifest) {
          log.warn("No audit events found for archive export (start={}, end={})", dateRangeStart, dateRangeEnd);
          return null;
        }
        Instant now = Instant.now();
        AuditArchiveManifest emptyManifest =
            AuditArchiveManifest.builder()
                .archiveYear(archiveFile.archiveYear())
                .archiveMonth(archiveFile.archiveMonth())
                .s3GlacierPath("gs://" + archiveBucketName + "/" + gcsObjectPath)
                .rowCount(0L)
                .compressedSizeBytes(0L)
                .checksumSha256(sha256Hex(new byte[0]))
                .dateRangeStart(dateRangeStart)
                .dateRangeEnd(dateRangeEnd)
                .archivedAt(now)
                .retrievableUntil(now.plus(retentionDays, ChronoUnit.DAYS))
                .verificationStatus("VERIFIED")
                .creationMethod(creationMethod)
                .build();
        emptyManifest.setLastVerificationAt(now);
        emptyManifest.setVerificationAttemptCount(1);
        return manifestRepository.save(emptyManifest);
      }

      validateEncryptionConfig();
      uploadToGcsGlacier(archiveFile);

      AuditArchiveManifest manifest =
          createManifest(archiveFile, dateRangeStart, dateRangeEnd, creationMethod);
      manifest = manifestRepository.save(manifest);

      return verifyArchiveIntegrity(manifest, gcsObjectPath);
    } catch (Exception ex) {
      log.error("Archive export pipeline failed for path={}", gcsObjectPath, ex);
      if (ex instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Archive export failed for " + gcsObjectPath, ex);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException cleanupEx) {
          log.warn("Failed deleting temporary archive file {}", tempFile, cleanupEx);
        }
      }
    }
  }

  private void validateEncryptionConfig() {
    if (enableEncryption && (kmsKeyId == null || kmsKeyId.isBlank())) {
      throw new IllegalStateException(
          "Audit archive encryption is enabled (audit.archive.enable-encryption=true) "
              + "but audit.archive.kms-key-id is not configured");
    }
  }

  private ArchiveFile serializeCompressedNdjson(
      String gcsObjectPath, Instant dateRangeStart, Instant dateRangeEnd)
      throws IOException, NoSuchAlgorithmException {
    OffsetDateTime utcStart = dateRangeStart.atOffset(ZoneOffset.UTC);
    Path tempFile = Files.createTempFile("audit-archive-", ".ndjson.gz");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");

    long rowCount = 0L;
    Long firstSeq = null;
    Long lastSeq = null;
    String lastHash = null;

    try (BufferedOutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(tempFile));
        DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest);
        GZIPOutputStream gzipOut = new GZIPOutputStream(digestOut);
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(gzipOut, StandardCharsets.UTF_8))) {
      int page = 0;
      while (true) {
        List<AuditEvent> batch =
            auditEventRepository.findForArchiveWindow(
                dateRangeStart, dateRangeEnd, PageRequest.of(page, EXPORT_BATCH_SIZE));
        if (batch == null || batch.isEmpty()) {
          break;
        }
        for (AuditEvent event : batch) {
          writer.write(objectMapper.writeValueAsString(event));
          writer.newLine();
          rowCount++;
          Long seq = event.getSequenceNumber();
          if (seq != null) {
            if (firstSeq == null || seq < firstSeq) {
              firstSeq = seq;
            }
            if (lastSeq == null || seq > lastSeq) {
              lastSeq = seq;
              lastHash = event.getEventHash();
            }
          }
        }
        if (batch.size() < EXPORT_BATCH_SIZE) {
          break;
        }
        page++;
      }
    }

    if (rowCount == 0L) {
      Files.deleteIfExists(tempFile);
      return new ArchiveFile(
          utcStart.getYear(),
          utcStart.getMonthValue(),
          gcsObjectPath,
          null,
          0L,
          sha256Hex(new byte[0]),
          0L,
          null,
          null,
          null);
    }

    long compressedSizeBytes = Files.size(tempFile);
    String checksum = HexFormat.of().formatHex(digest.digest());
    return new ArchiveFile(
        utcStart.getYear(),
        utcStart.getMonthValue(),
        gcsObjectPath,
        tempFile,
        compressedSizeBytes,
        checksum,
        rowCount,
        firstSeq,
        lastSeq,
        lastHash);
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
        if (enableEncryption) {
          blob =
              storage.createFrom(
                  blobInfo,
                  archiveFile.tempFile(),
                  Storage.BlobWriteOption.kmsKeyName(kmsKeyId));
        } else {
          blob = storage.createFrom(blobInfo, archiveFile.tempFile());
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
      ArchiveFile archiveFile,
      Instant dateRangeStart,
      Instant dateRangeEnd,
      String creationMethod) {
    Instant now = Instant.now();
    return AuditArchiveManifest.builder()
        .archiveYear(archiveFile.archiveYear())
        .archiveMonth(archiveFile.archiveMonth())
        .s3GlacierPath("gs://" + archiveBucketName + "/" + archiveFile.gcsPath())
        .rowCount(archiveFile.rowCount())
        .compressedSizeBytes(archiveFile.compressedSizeBytes())
        .checksumSha256(archiveFile.checksumSha256())
        .dateRangeStart(dateRangeStart)
        .dateRangeEnd(dateRangeEnd)
        .archivedAt(now)
        .retrievableUntil(now.plus(retentionDays, ChronoUnit.DAYS))
        .verificationStatus("PENDING")
        .creationMethod(creationMethod)
        .firstSequence(archiveFile.firstSequence())
        .lastSequence(archiveFile.lastSequence())
        .lastEventHash(archiveFile.lastEventHash())
        .build();
  }

  private AuditArchiveManifest verifyArchiveIntegrity(
      AuditArchiveManifest manifest, String gcsObjectPath) {
    try {
      Storage storage = resolveStorageClient();
      Blob blob = storage.get(BlobId.of(archiveBucketName, gcsObjectPath));
      if (blob == null || blob.getSize() == null || blob.getSize() <= 0) {
        throw new IllegalStateException(
            "Archive blob missing or empty after upload: " + manifest.getS3GlacierPath());
      }

      String actualSha256 = computeRemoteSha256(blob);
      if (actualSha256 == null) {
        throw new IllegalStateException(
            "Archive blob content empty or unreadable after upload: " + manifest.getS3GlacierPath());
      }
      if (!actualSha256.equals(manifest.getChecksumSha256())) {
        throw new IllegalStateException(
            "Archive SHA-256 verification failed for "
                + manifest.getS3GlacierPath()
                + ": expected="
                + manifest.getChecksumSha256()
                + ", actual="
                + actualSha256);
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

  private static String computeRemoteSha256(Blob blob) throws IOException, NoSuchAlgorithmException {
    ReadChannel reader = blob.reader();
    if (reader != null) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long totalRead = 0L;
      byte[] buffer = new byte[STREAM_BUFFER_SIZE];
      try (InputStream in = Channels.newInputStream(reader)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
          totalRead += read;
        }
      }
      return totalRead > 0 ? HexFormat.of().formatHex(digest.digest()) : null;
    }
    byte[] remoteBytes = blob.getContent();
    if (remoteBytes == null || remoteBytes.length == 0) {
      return null;
    }
    return sha256Hex(remoteBytes);
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
      Path tempFile,
      long compressedSizeBytes,
      String checksumSha256,
      long rowCount,
      Long firstSequence,
      Long lastSequence,
      String lastEventHash) {}
}
