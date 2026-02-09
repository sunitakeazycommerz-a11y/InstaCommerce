package com.instacommerce.fulfillment.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pick_tasks")
public class PickTask {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Long version;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_erased", nullable = false)
    private boolean userErased;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "picker_id")
    private UUID pickerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "pick_task_status")
    private PickTaskStatus status = PickTaskStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @OneToMany(mappedBy = "pickTask", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PickItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isUserErased() {
        return userErased;
    }

    public void setUserErased(boolean userErased) {
        this.userErased = userErased;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public UUID getPickerId() {
        return pickerId;
    }

    public void setPickerId(UUID pickerId) {
        this.pickerId = pickerId;
    }

    public PickTaskStatus getStatus() {
        return status;
    }

    public void setStatus(PickTaskStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<PickItem> getItems() {
        return items;
    }

    public void setItems(List<PickItem> items) {
        this.items = items;
    }
}
