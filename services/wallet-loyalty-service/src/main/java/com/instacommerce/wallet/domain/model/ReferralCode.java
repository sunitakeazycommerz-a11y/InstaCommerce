package com.instacommerce.wallet.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "referral_codes")
public class ReferralCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false)
    private int uses;

    @Column(name = "max_uses", nullable = false)
    private int maxUses = 10;

    @Column(name = "reward_cents", nullable = false)
    private long rewardCents = 5000;

    @Column(nullable = false)
    private boolean active = true;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public int getUses() { return uses; }
    public void setUses(int uses) { this.uses = uses; }

    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }

    public long getRewardCents() { return rewardCents; }
    public void setRewardCents(long rewardCents) { this.rewardCents = rewardCents; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
