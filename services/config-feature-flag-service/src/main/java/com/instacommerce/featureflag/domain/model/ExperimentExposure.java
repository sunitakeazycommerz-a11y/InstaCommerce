package com.instacommerce.featureflag.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "experiment_exposures")
public class ExperimentExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "variant_name", nullable = false, length = 100)
    private String variantName;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "assignment_key", length = 255)
    private String assignmentKey;

    @Column(name = "switchback_window")
    private Long switchbackWindow;

    @Column(length = 30)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String context;

    @Column(name = "exposed_at", nullable = false)
    private Instant exposedAt = Instant.now();

    protected ExperimentExposure() {
    }

    public ExperimentExposure(UUID experimentId, UUID variantId, String variantName, UUID userId,
                              String assignmentKey, Long switchbackWindow, String source, String context) {
        this.experimentId = experimentId;
        this.variantId = variantId;
        this.variantName = variantName;
        this.userId = userId;
        this.assignmentKey = assignmentKey;
        this.switchbackWindow = switchbackWindow;
        this.source = source;
        this.context = context;
        this.exposedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getVariantName() {
        return variantName;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAssignmentKey() {
        return assignmentKey;
    }

    public Long getSwitchbackWindow() {
        return switchbackWindow;
    }

    public String getSource() {
        return source;
    }

    public String getContext() {
        return context;
    }

    public Instant getExposedAt() {
        return exposedAt;
    }
}
