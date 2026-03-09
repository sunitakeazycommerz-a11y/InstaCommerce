package com.instacommerce.checkout.workflow;

import java.util.Objects;

/**
 * Deterministic, workflow-stable idempotency keys for payment operations.
 * <p>
 * Keys are derived solely from the Temporal workflowId (and, post-auth, the
 * paymentId returned by payment-service). They never incorporate values that
 * change across Temporal activity retries or workflow resets/replays, such as
 * the activity execution id.
 * <p>
 * Format: {@code {workflowId}:pay:auth}, {@code {workflowId}:pay:{paymentId}:capture}, etc.
 * The colon separator is chosen to visually distinguish structural segments
 * from the hyphens already present in UUIDs and workflow ids.
 */
public final class PaymentOperationKeys {

    private final String workflowId;

    public PaymentOperationKeys(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be null or blank");
        }
        this.workflowId = workflowId;
    }

    /** Key for the authorize call (before paymentId is known). */
    public String authorize() {
        return workflowId + ":pay:auth";
    }

    /** Key for capturing an authorized payment. */
    public String capture(String paymentId) {
        return operationKey(paymentId, "capture");
    }

    /** Key for voiding an authorized payment. */
    public String voidOp(String paymentId) {
        return operationKey(paymentId, "void");
    }

    /** Key for refunding a captured payment. */
    public String refund(String paymentId) {
        return operationKey(paymentId, "refund");
    }

    public String workflowId() {
        return workflowId;
    }

    private String operationKey(String paymentId, String operation) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId must not be null or blank for " + operation);
        }
        return workflowId + ":pay:" + paymentId + ":" + operation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentOperationKeys that)) return false;
        return workflowId.equals(that.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflowId);
    }

    @Override
    public String toString() {
        return "PaymentOperationKeys{workflowId='" + workflowId + "'}";
    }
}
