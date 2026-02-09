package com.instacommerce.riderfleet.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rider_earnings",
    uniqueConstraints = @UniqueConstraint(name = "uk_rider_earnings_order_id", columnNames = "order_id"))
public class RiderEarning {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "delivery_fee_cents", nullable = false)
    private long deliveryFeeCents;

    @Column(name = "tip_cents")
    private long tipCents;

    @Column(name = "incentive_cents")
    private long incentiveCents;

    @Column(name = "earned_at")
    private Instant earnedAt;

    @PrePersist
    void prePersist() {
        if (earnedAt == null) {
            earnedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public void setRiderId(UUID riderId) {
        this.riderId = riderId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public long getDeliveryFeeCents() {
        return deliveryFeeCents;
    }

    public void setDeliveryFeeCents(long deliveryFeeCents) {
        this.deliveryFeeCents = deliveryFeeCents;
    }

    public long getTipCents() {
        return tipCents;
    }

    public void setTipCents(long tipCents) {
        this.tipCents = tipCents;
    }

    public long getIncentiveCents() {
        return incentiveCents;
    }

    public void setIncentiveCents(long incentiveCents) {
        this.incentiveCents = incentiveCents;
    }

    public Instant getEarnedAt() {
        return earnedAt;
    }

    public void setEarnedAt(Instant earnedAt) {
        this.earnedAt = earnedAt;
    }
}
