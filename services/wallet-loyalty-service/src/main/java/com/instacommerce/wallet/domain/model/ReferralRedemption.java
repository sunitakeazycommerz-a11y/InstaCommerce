package com.instacommerce.wallet.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "referral_redemptions")
public class ReferralRedemption {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_code_id", nullable = false)
    private ReferralCode referralCode;

    @Column(name = "referred_user_id", nullable = false, unique = true)
    private UUID referredUserId;

    @Column(name = "reward_credited", nullable = false)
    private boolean rewardCredited;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ReferralCode getReferralCode() { return referralCode; }
    public void setReferralCode(ReferralCode referralCode) { this.referralCode = referralCode; }

    public UUID getReferredUserId() { return referredUserId; }
    public void setReferredUserId(UUID referredUserId) { this.referredUserId = referredUserId; }

    public boolean isRewardCredited() { return rewardCredited; }
    public void setRewardCredited(boolean rewardCredited) { this.rewardCredited = rewardCredited; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
