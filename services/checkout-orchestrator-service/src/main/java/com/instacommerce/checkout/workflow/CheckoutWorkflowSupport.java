package com.instacommerce.checkout.workflow;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.PaymentAuthResult;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.workflow.activity.CompensationDlqActivity;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CheckoutWorkflowSupport {

    static final String INSUFFICIENT_STOCK_EXCEPTION =
        "com.instacommerce.checkout.exception.InsufficientStockException";
    static final String PAYMENT_DECLINED_EXCEPTION =
        "com.instacommerce.checkout.exception.PaymentDeclinedException";
    static final String INVALID_COUPON_EXCEPTION =
        "com.instacommerce.pricing.exception.InvalidCouponException";
    static final String COUPON_NOT_FOUND_EXCEPTION =
        "com.instacommerce.pricing.exception.CouponNotFoundException";

    private static final Map<String, String> FAILURE_MESSAGES = Map.of(
        "VALIDATING_CART", "Cart validation failed. Please retry.",
        "CALCULATING_PRICES", "Pricing calculation unavailable. Please retry.",
        "RESERVING_INVENTORY", "Unable to reserve inventory. Please retry.",
        "DEBITING_WALLET", "Wallet debit failed. Please retry.",
        "AUTHORIZING_PAYMENT", "Payment authorization failed. Please retry.",
        "CREATING_ORDER", "Order creation failed. Please retry.",
        "REDEEMING_COUPON", "Coupon redemption failed. Please retry.",
        "CONFIRMING", "Order confirmation failed. Please retry."
    );

    private CheckoutWorkflowSupport() {
    }

    static <T> T activityStub(Class<T> iface,
                              int startToCloseSeconds,
                              int initialIntervalSeconds,
                              int maxAttempts,
                              int scheduleToCloseSeconds,
                              String... doNotRetry) {
        RetryOptions.Builder retry = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(initialIntervalSeconds))
            .setMaximumAttempts(maxAttempts)
            .setBackoffCoefficient(2.0);
        if (doNotRetry.length > 0) {
            retry.setDoNotRetry(doNotRetry);
        }
        ActivityOptions.Builder options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(startToCloseSeconds))
            .setRetryOptions(retry.build());
        if (scheduleToCloseSeconds > 0) {
            options.setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseSeconds));
        }
        return Workflow.newActivityStub(iface, options.build());
    }

    static String failureMessageFor(String failedStep) {
        return FAILURE_MESSAGES.getOrDefault(failedStep, "Checkout failed. Please retry.");
    }

    static String deterministicOrderId(String workflowId) {
        return UUID.nameUUIDFromBytes(("order-" + workflowId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String resolveCurrency(PricingResult pricingResult) {
        return (pricingResult.currency() != null && !pricingResult.currency().isBlank())
            ? pricingResult.currency()
            : PricingResult.DEFAULT_CURRENCY;
    }

    static String resolveAppliedCoupon(PricingResult pricingResult) {
        return (pricingResult.appliedCoupon() != null && !pricingResult.appliedCoupon().isBlank())
            ? pricingResult.appliedCoupon()
            : null;
    }

    static OrderCreateRequest buildOrderCreateRequest(String workflowId,
                                                      String expectedOrderId,
                                                      CheckoutRequest request,
                                                      String storeId,
                                                      List<CartItem> items,
                                                      PricingResult pricingResult,
                                                      String currency,
                                                      String appliedCoupon,
                                                      String reservationId,
                                                      PaymentAuthResult paymentResult,
                                                      long walletDebitedCents) {
        return OrderCreateRequest.builder()
            .userId(request.userId())
            .storeId(storeId)
            .items(items)
            .subtotalCents(pricingResult.subtotalCents())
            .discountCents(pricingResult.discountCents())
            .deliveryFeeCents(pricingResult.deliveryFeeCents())
            .totalCents(pricingResult.totalCents())
            .currency(currency)
            .couponCode(appliedCoupon)
            .reservationId(reservationId)
            .paymentId(paymentResult != null ? paymentResult.paymentId() : null)
            .deliveryAddressId(request.deliveryAddressId())
            .paymentMethodId(request.paymentMethodId())
            .quoteId(pricingResult.quoteId())
            .quoteToken(pricingResult.quoteToken())
            .idempotencyKey(workflowId + ":order:create")
            .dropoffLat(request.dropoffLat())
            .dropoffLng(request.dropoffLng())
            .orderId(expectedOrderId)
            .walletAmountCents(walletDebitedCents)
            .build();
    }

    static void compensatePayment(String workflowId,
                                  PaymentOperationKeys paymentKeys,
                                  PaymentActivity compensationPaymentActivity,
                                  CompensationDlqActivity dlqActivity,
                                  PaymentAuthResult paymentResult,
                                  boolean paymentAuthorized,
                                  boolean paymentCaptured,
                                  boolean paymentCaptureAttempted,
                                  Long paymentAmountCents) {
        if (!paymentAuthorized || paymentResult == null) {
            return;
        }
        if (paymentAmountCents == null || paymentAmountCents <= 0) {
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .warn("Skipping refund for payment {} due to missing amount", paymentResult.paymentId());
            return;
        }
        String paymentId = paymentResult.paymentId();
        String refundIdempotencyKey = paymentKeys.refund(paymentId);
        String voidIdempotencyKey = paymentKeys.voidOp(paymentId);
        String step = paymentCaptured ? "refundPayment" : "voidPayment";
        runWithDlq(workflowId, dlqActivity, step, paymentId, String.valueOf(paymentAmountCents), () -> {
            if (paymentCaptured) {
                compensationPaymentActivity.refundPayment(paymentId, paymentAmountCents, refundIdempotencyKey);
                return;
            }
            try {
                compensationPaymentActivity.voidPayment(paymentId, voidIdempotencyKey);
            } catch (Exception voidFailure) {
                if (paymentCaptureAttempted) {
                    Workflow.getLogger(CheckoutWorkflowImpl.class)
                        .warn("Void failed for payment {} after capture attempt, trying refund: {}",
                            paymentId, voidFailure.getMessage());
                    compensationPaymentActivity.refundPayment(paymentId, paymentAmountCents, refundIdempotencyKey);
                    return;
                }
                throw voidFailure;
            }
        });
    }

    static void safeRollbackCoupon(String workflowId,
                                   CompensationDlqActivity dlqActivity,
                                   CouponActivity compensationCouponActivity,
                                   String couponCode,
                                   String userId,
                                   String orderId) {
        boolean hasOrder = orderId != null && !orderId.isBlank();
        String step = hasOrder ? "unredeemCoupon" : "rollbackUsage";
        String targetId = hasOrder ? couponCode + ":" + userId + ":" + orderId : couponCode + ":" + userId;
        runWithDlq(workflowId, dlqActivity, step, targetId, null, () -> {
            if (hasOrder) {
                compensationCouponActivity.unredeemCoupon(couponCode, userId, orderId);
            } else {
                compensationCouponActivity.rollbackUsage(couponCode, userId);
            }
        });
    }

    static void runWithDlq(String workflowId,
                           CompensationDlqActivity dlqActivity,
                           String step,
                           String targetId,
                           String payload,
                           Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to compensate step={} targetId={}, routing to DLQ: {}", step, targetId, e.getMessage());
            dlqActivity.recordFailedCompensation(workflowId, step, targetId, e.getMessage(), payload);
        }
    }

    static String sanitizeClientReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        String sanitized = reason.replaceAll("[\\r\\n]+", " ").trim();
        return sanitized.length() > 160 ? fallback : sanitized;
    }
}
