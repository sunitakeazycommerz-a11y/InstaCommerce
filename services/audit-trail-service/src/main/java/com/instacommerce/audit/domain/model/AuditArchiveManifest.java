package com.instacommerce.audit.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_archive_manifests")
public class AuditArchiveManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false)
    private UUID id;

    @Column(name = "archive_year", nullable = false)
    private Integer archiveYear;

    @Column(name = "archive_month", nullable = false)
    private Integer archiveMonth;

    @Column(name = "s3_glacier_path", nullable = false, length = 512)
    private String s3GlacierPath;

    @Column(name = "row_count", nullable = false)
    private Long rowCount;

    @Column(name = "compressed_size_bytes", nullable = false)
    private Long compressedSizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "date_range_start", nullable = false)
    private Instant dateRangeStart;

    @Column(name = "date_range_end", nullable = false)
    private Instant dateRangeEnd;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt;

    @Column(name = "retrievable_until", nullable = false)
    private Instant retrievableUntil;

    @Column(name = "verification_status", nullable = false, length = 50)
    private String verificationStatus;

    @Column(name = "creation_method", length = 50)
    private String creationMethod;

    @Column(name = "last_verification_at")
    private Instant lastVerificationAt;

    @Column(name = "verification_attempt_count")
    private Integer verificationAttemptCount;

    public AuditArchiveManifest() {}

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getArchiveYear() {
        return archiveYear;
    }

    public void setArchiveYear(Integer archiveYear) {
        this.archiveYear = archiveYear;
    }

    public Integer getArchiveMonth() {
        return archiveMonth;
    }

    public void setArchiveMonth(Integer archiveMonth) {
        this.archiveMonth = archiveMonth;
    }

    public String getS3GlacierPath() {
        return s3GlacierPath;
    }

    public void setS3GlacierPath(String s3GlacierPath) {
        this.s3GlacierPath = s3GlacierPath;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    public Long getCompressedSizeBytes() {
        return compressedSizeBytes;
    }

    public void setCompressedSizeBytes(Long compressedSizeBytes) {
        this.compressedSizeBytes = compressedSizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public Instant getDateRangeStart() {
        return dateRangeStart;
    }

    public void setDateRangeStart(Instant dateRangeStart) {
        this.dateRangeStart = dateRangeStart;
    }

    public Instant getDateRangeEnd() {
        return dateRangeEnd;
    }

    public void setDateRangeEnd(Instant dateRangeEnd) {
        this.dateRangeEnd = dateRangeEnd;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getRetrievableUntil() {
        return retrievableUntil;
    }

    public void setRetrievableUntil(Instant retrievableUntil) {
        this.retrievableUntil = retrievableUntil;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getCreationMethod() {
        return creationMethod;
    }

    public void setCreationMethod(String creationMethod) {
        this.creationMethod = creationMethod;
    }

    public Instant getLastVerificationAt() {
        return lastVerificationAt;
    }

    public void setLastVerificationAt(Instant lastVerificationAt) {
        this.lastVerificationAt = lastVerificationAt;
    }

    public Integer getVerificationAttemptCount() {
        return verificationAttemptCount;
    }

    public void setVerificationAttemptCount(Integer verificationAttemptCount) {
        this.verificationAttemptCount = verificationAttemptCount;
    }

    public static class Builder {
        private final AuditArchiveManifest manifest = new AuditArchiveManifest();

        public Builder archiveYear(Integer archiveYear) {
            manifest.setArchiveYear(archiveYear);
            return this;
        }

        public Builder archiveMonth(Integer archiveMonth) {
            manifest.setArchiveMonth(archiveMonth);
            return this;
        }

        public Builder s3GlacierPath(String s3GlacierPath) {
            manifest.setS3GlacierPath(s3GlacierPath);
            return this;
        }

        public Builder rowCount(Long rowCount) {
            manifest.setRowCount(rowCount);
            return this;
        }

        public Builder compressedSizeBytes(Long compressedSizeBytes) {
            manifest.setCompressedSizeBytes(compressedSizeBytes);
            return this;
        }

        public Builder checksumSha256(String checksumSha256) {
            manifest.setChecksumSha256(checksumSha256);
            return this;
        }

        public Builder dateRangeStart(Instant dateRangeStart) {
            manifest.setDateRangeStart(dateRangeStart);
            return this;
        }

        public Builder dateRangeEnd(Instant dateRangeEnd) {
            manifest.setDateRangeEnd(dateRangeEnd);
            return this;
        }

        public Builder archivedAt(Instant archivedAt) {
            manifest.setArchivedAt(archivedAt);
            return this;
        }

        public Builder retrievableUntil(Instant retrievableUntil) {
            manifest.setRetrievableUntil(retrievableUntil);
            return this;
        }

        public Builder verificationStatus(String verificationStatus) {
            manifest.setVerificationStatus(verificationStatus);
            return this;
        }

        public Builder creationMethod(String creationMethod) {
            manifest.setCreationMethod(creationMethod);
            return this;
        }

        public AuditArchiveManifest build() {
            return manifest;
        }
    }
}
