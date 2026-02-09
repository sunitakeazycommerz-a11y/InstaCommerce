package com.instacommerce.wallet.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "wallet_transactions")
public class WalletTransaction {
    public enum Type { CREDIT, DEBIT }
    public enum ReferenceType { ORDER, REFUND, TOPUP, CASHBACK, REFERRAL, PROMOTION, ADMIN_ADJUSTMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "balance_after_cents", nullable = false)
    private long balanceAfterCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 20)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private String referenceId;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Wallet getWallet() { return wallet; }
    public void setWallet(Wallet wallet) { this.wallet = wallet; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

    public long getBalanceAfterCents() { return balanceAfterCents; }
    public void setBalanceAfterCents(long balanceAfterCents) { this.balanceAfterCents = balanceAfterCents; }

    public ReferenceType getReferenceType() { return referenceType; }
    public void setReferenceType(ReferenceType referenceType) { this.referenceType = referenceType; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
