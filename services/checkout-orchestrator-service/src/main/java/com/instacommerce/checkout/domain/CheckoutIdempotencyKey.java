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
@Table(name = "checkout_idempotency_keys")
public class CheckoutIdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "checkout_response", nullable = false, columnDefinition = "TEXT")
    private String checkoutResponse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected CheckoutIdempotencyKey() {}

    public CheckoutIdempotencyKey(String idempotencyKey, String checkoutResponse, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.checkoutResponse = checkoutResponse;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCheckoutResponse() { return checkoutResponse; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
