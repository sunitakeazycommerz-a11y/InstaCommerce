package com.instacommerce.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compensation_dlq")
public class CompensationDlq {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "activity_name", nullable = false)
    private String activityName;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompensationDlq() {}

    public CompensationDlq(String workflowId, String activityName, String targetId, String reason, String payload) {
        this.workflowId = workflowId;
        this.activityName = activityName;
        this.targetId = targetId;
        this.reason = reason;
        this.payload = payload;
        this.status = "FAILED";
        this.retryCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getActivityName() { return activityName; }
    public String getTargetId() { return targetId; }
    public String getReason() { return reason; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markResolved(String resolutionNote) {
        this.status = "RESOLVED";
        this.reason = this.reason + " [Resolved: " + resolutionNote + "]";
        this.updatedAt = Instant.now();
    }

    public void incrementRetry() {
        this.retryCount++;
        this.updatedAt = Instant.now();
    }
}
