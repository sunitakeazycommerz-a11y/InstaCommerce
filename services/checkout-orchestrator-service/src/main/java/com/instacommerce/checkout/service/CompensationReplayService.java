package com.instacommerce.checkout.service;

import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.workflow.PaymentOperationKeys;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import org.springframework.stereotype.Service;

/**
 * Replays failed saga compensation operations recorded in {@link CompensationDlq}
 * against downstream inventory, payment, order, and pricing services.
 */
@Service
public class CompensationReplayService {

    private final InventoryActivity inventoryActivity;
    private final PaymentActivity paymentActivity;
    private final OrderActivity orderActivity;
    private final CouponActivity couponActivity;

    public CompensationReplayService(InventoryActivity inventoryActivity,
                                     PaymentActivity paymentActivity,
                                     OrderActivity orderActivity,
                                     CouponActivity couponActivity) {
        this.inventoryActivity = inventoryActivity;
        this.paymentActivity = paymentActivity;
        this.orderActivity = orderActivity;
        this.couponActivity = couponActivity;
    }

    public void replay(CompensationDlq entry) {
        if (entry == null) {
            throw new IllegalArgumentException("DLQ entry must not be null");
        }
        String activityName = requireNonBlank(entry.getActivityName(), "activityName");
        String targetId = requireNonBlank(entry.getTargetId(), "targetId");
        switch (activityName) {
            case "releaseStock" -> inventoryActivity.releaseStock(targetId);
            case "voidPayment" -> paymentActivity.voidPayment(
                targetId, paymentKeysFor(entry).voidOp(targetId));
            case "refundPayment" -> paymentActivity.refundPayment(
                targetId, parsePositiveAmountCents(entry.getPayload()), paymentKeysFor(entry).refund(targetId));
            case "cancelOrder" -> orderActivity.cancelOrder(targetId);
            case "rollbackUsage" -> replayRollbackUsage(targetId);
            default -> throw new IllegalArgumentException("Unsupported DLQ compensation activity: " + activityName);
        }
    }

    private PaymentOperationKeys paymentKeysFor(CompensationDlq entry) {
        return new PaymentOperationKeys(requireNonBlank(entry.getWorkflowId(), "workflowId"));
    }

    private long parsePositiveAmountCents(String payload) {
        String raw = requireNonBlank(payload, "payload (amountCents)");
        long amountCents;
        try {
            amountCents = Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid refund amount payload: " + payload, ex);
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Refund amountCents must be positive: " + amountCents);
        }
        return amountCents;
    }

    private void replayRollbackUsage(String targetId) {
        String[] parts = targetId.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                "Invalid rollbackUsage targetId (expected couponCode:userId): " + targetId);
        }
        couponActivity.rollbackUsage(parts[0], parts[1]);
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DLQ entry " + field + " must not be blank");
        }
        return value;
    }
}
