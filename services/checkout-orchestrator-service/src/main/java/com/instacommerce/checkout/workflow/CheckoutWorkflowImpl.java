package com.instacommerce.checkout.workflow;

import com.instacommerce.checkout.domain.PriceDriftEvaluator;
import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.CartValidationResult;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.InventoryReserveRequest;
import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.dto.PaymentAuthResult;
import com.instacommerce.checkout.dto.PricingRequest;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.dto.WalletDebitResult;
import com.instacommerce.checkout.workflow.activity.CartActivity;
import com.instacommerce.checkout.workflow.activity.CompensationDlqActivity;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.PricingActivity;
import com.instacommerce.checkout.workflow.activity.WalletActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CheckoutWorkflowImpl implements CheckoutWorkflow {
    private static final String INSUFFICIENT_STOCK_EXCEPTION =
        "com.instacommerce.checkout.exception.InsufficientStockException";
    private static final String PAYMENT_DECLINED_EXCEPTION =
        "com.instacommerce.checkout.exception.PaymentDeclinedException";
    private static final String INVALID_COUPON_EXCEPTION =
        "com.instacommerce.pricing.exception.InvalidCouponException";
    private static final String COUPON_NOT_FOUND_EXCEPTION =
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

    private final String workflowId = Workflow.getInfo().getWorkflowId();
    // Derived from workflowId so Temporal activity retries reuse identical idempotency keys.
    private final PaymentOperationKeys paymentKeys = new PaymentOperationKeys(workflowId);

    private final CartActivity cartActivity = activityStub(CartActivity.class, 10, 1, 3, 0);
    private final PricingActivity pricingActivity = activityStub(PricingActivity.class, 10, 1, 3, 0);
    private final InventoryActivity inventoryActivity =
        activityStub(InventoryActivity.class, 15, 1, 3, 0, INSUFFICIENT_STOCK_EXCEPTION);
    private final PaymentActivity paymentActivity =
        activityStub(PaymentActivity.class, 30, 2, 3, 45, PAYMENT_DECLINED_EXCEPTION);
    private final OrderActivity orderActivity = activityStub(OrderActivity.class, 15, 1, 3, 0);
    private final CouponActivity couponActivity =
        activityStub(CouponActivity.class, 10, 1, 3, 0, INVALID_COUPON_EXCEPTION, COUPON_NOT_FOUND_EXCEPTION);
    private final InventoryActivity compensationInventoryActivity = activityStub(InventoryActivity.class, 30, 2, 5, 0);
    private final PaymentActivity compensationPaymentActivity = activityStub(PaymentActivity.class, 45, 1, 5, 0);
    private final OrderActivity compensationOrderActivity = activityStub(OrderActivity.class, 30, 2, 5, 0);
    private final CouponActivity compensationCouponActivity = activityStub(CouponActivity.class, 20, 2, 5, 0);
    private final CompensationDlqActivity dlqActivity = activityStub(CompensationDlqActivity.class, 15, 1, 3, 0);

    private String currentStatus = "STARTED";
    private CheckoutResponse lastResponse;
    private WalletActivity walletActivity;
    private WalletActivity compensationWalletActivity;

    private static <T> T activityStub(Class<T> iface,
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

    private WalletActivity walletActivity() {
        if (walletActivity == null) {
            walletActivity = activityStub(WalletActivity.class, 15, 1, 3, 0);
        }
        return walletActivity;
    }

    private WalletActivity compensationWalletActivity() {
        if (compensationWalletActivity == null) {
            compensationWalletActivity = activityStub(WalletActivity.class, 30, 2, 5, 0);
        }
        return compensationWalletActivity;
    }

    @Override
    public CheckoutResponse checkout(CheckoutRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder()
            .setParallelCompensation(false)
            .setContinueWithError(true)
            .build());
        PaymentAuthResult paymentResult = null;
        Long paymentAmountCents = null;
        boolean paymentAuthorized = false;
        boolean paymentCaptured = false;
        boolean paymentCaptureAttempted = false;

        try {
            currentStatus = "VALIDATING_CART";
            CartValidationResult cartResult = cartActivity.validateCart(request.userId());
            if (!cartResult.valid() || cartResult.items() == null || cartResult.items().isEmpty()) {
                return fail("Cart is empty or invalid");
            }
            List<CartItem> items = cartResult.items();
            if (items.stream().anyMatch(i -> i == null || i.quantity() == null || i.quantity() <= 0)) {
                return fail("Cart contains items with invalid or non-positive quantities");
            }
            String storeId = cartResult.storeId();

            currentStatus = "CALCULATING_PRICES";
            PricingRequest pricingRequest = new PricingRequest(
                request.userId(), items, storeId, request.couponCode(), request.deliveryAddressId());
            PricingResult pricingResult = pricingActivity.calculatePrice(pricingRequest);
            if (pricingResult == null || pricingResult.totalCents() < 0) {
                return fail("Pricing calculation failed: invalid total amount");
            }
            PriceDriftEvaluator.Evaluation priceDrift = PriceDriftEvaluator.evaluate(
                pricingResult.totalCents(),
                request.expectedTotalCents(),
                request.maxPriceDriftCents());
            if (!priceDrift.withinTolerance()) {
                return fail(priceDrift.failureReason());
            }
            long totalAmountCents = pricingResult.totalCents();
            String currency = (pricingResult.currency() != null && !pricingResult.currency().isBlank())
                ? pricingResult.currency()
                : PricingResult.DEFAULT_CURRENCY;
            UUID deterministicOrderId = UUID.nameUUIDFromBytes(
                ("order-" + workflowId).getBytes(StandardCharsets.UTF_8));
            String expectedOrderId = deterministicOrderId.toString();

            // Register each compensation immediately around its reservation/debit step.
            currentStatus = "RESERVING_INVENTORY";
            InventoryReservationResult reservationResult = inventoryActivity.reserveStock(
                new InventoryReserveRequest(workflowId + ":inventory:reserve", storeId, items));
            if (!reservationResult.reserved()) {
                return fail("Some items are out of stock");
            }
            saga.addCompensation(() -> safeReleaseStock(reservationResult.reservationId()));

            long walletDebitedCents = 0L;
            long requestedWalletCents = request.walletAmountCents() != null
                ? Math.min(request.walletAmountCents(), totalAmountCents)
                : 0L;
            if (requestedWalletCents > 0) {
                currentStatus = "DEBITING_WALLET";
                WalletDebitResult walletResult = walletActivity().debitWallet(
                    request.userId(),
                    requestedWalletCents,
                    paymentKeys.walletDebit(),
                    "Checkout split-tender payment " + workflowId);
                if (walletResult == null || !walletResult.debited()) {
                    currentStatus = "COMPENSATING";
                    saga.compensate();
                    return fail("Wallet debit failed: " + sanitizeClientReason(
                        walletResult != null ? walletResult.failureReason() : null, "Wallet debit declined"));
                }
                final long appliedWalletCents = walletResult.amountCents() > 0
                    ? Math.min(walletResult.amountCents(), totalAmountCents)
                    : requestedWalletCents;
                walletDebitedCents = appliedWalletCents;
                saga.addCompensation(() -> safeRefundWallet(request.userId(), appliedWalletCents));
            }
            paymentAmountCents = totalAmountCents - walletDebitedCents;

            if (paymentAmountCents > 0) {
                currentStatus = "AUTHORIZING_PAYMENT";
                paymentResult = paymentActivity.authorizePayment(
                    expectedOrderId,
                    paymentAmountCents,
                    currency,
                    request.paymentMethodId(),
                    paymentKeys.authorize());
                if (paymentResult == null) {
                    throw new IllegalStateException("Payment authorization returned no result");
                }
                paymentAuthorized = paymentResult.authorized();
                if (paymentAuthorized && (paymentResult.paymentId() == null || paymentResult.paymentId().isBlank())) {
                    throw new IllegalStateException("Payment authorization succeeded without a paymentId");
                }

                if (!paymentAuthorized) {
                    currentStatus = "COMPENSATING";
                    saga.compensate();
                    if ("TOKEN_EXPIRED".equalsIgnoreCase(paymentResult.declineReason())) {
                        return fail("PAYMENT_TOKEN_EXPIRED: Client re-authentication required");
                    }
                    return fail("Payment declined: " + sanitizeClientReason(
                        paymentResult.declineReason(), "Payment declined"));
                }
            }

            String appliedCoupon = (pricingResult.appliedCoupon() != null && !pricingResult.appliedCoupon().isBlank())
                ? pricingResult.appliedCoupon()
                : null;
            long couponDiscountCents = Math.max(0L, pricingResult.couponDiscountCents());

            currentStatus = "CREATING_ORDER";
            OrderCreateRequest orderRequest = OrderCreateRequest.builder()
                .userId(request.userId())
                .storeId(storeId)
                .items(items)
                .subtotalCents(pricingResult.subtotalCents())
                .discountCents(pricingResult.discountCents())
                .deliveryFeeCents(pricingResult.deliveryFeeCents())
                .totalCents(pricingResult.totalCents())
                .currency(currency)
                .couponCode(appliedCoupon)
                .reservationId(reservationResult.reservationId())
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
            OrderCreationResult orderResult = orderActivity.createOrder(orderRequest);
            saga.addCompensation(() -> safeCancelOrder(orderResult.orderId()));

            if (appliedCoupon != null && couponDiscountCents > 0) {
                currentStatus = "REDEEMING_COUPON";
                couponActivity.redeemCoupon(
                    appliedCoupon,
                    request.userId(),
                    orderResult.orderId(),
                    couponDiscountCents);
                saga.addCompensation(() -> safeRollbackCoupon(appliedCoupon, request.userId(), orderResult.orderId()));
            }

            currentStatus = "CONFIRMING";
            inventoryActivity.confirmStock(reservationResult.reservationId());
            if (paymentAmountCents > 0 && paymentResult != null) {
                paymentCaptureAttempted = true;
                paymentActivity.capturePayment(
                    paymentResult.paymentId(),
                    paymentKeys.capture(paymentResult.paymentId()));
                paymentCaptured = true;
            }
            orderActivity.confirmOrder(orderResult.orderId(), workflowId + ":order:confirm");

            clearCartSafely(request.userId());

            currentStatus = "COMPLETED";
            lastResponse = CheckoutResponse.success(
                orderResult.orderId(),
                pricingResult.totalCents(),
                orderResult.estimatedDeliveryMinutes());
            return lastResponse;
        } catch (Exception e) {
            String failedStep = currentStatus;
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Checkout workflow failed step={} workflowId={}", failedStep, workflowId, e);
            currentStatus = "COMPENSATING";
            compensatePayment(
                paymentResult,
                paymentAuthorized,
                paymentCaptured,
                paymentCaptureAttempted,
                paymentAmountCents);
            saga.compensate();
            return fail(FAILURE_MESSAGES.getOrDefault(failedStep, "Checkout failed. Please retry."));
        }
    }

    @Override
    public String getStatus() {
        return currentStatus;
    }

    @Override
    public CheckoutResponse getResultSnapshot() {
        return lastResponse;
    }

    private CheckoutResponse fail(String reason) {
        currentStatus = "FAILED";
        lastResponse = CheckoutResponse.failed(reason);
        return lastResponse;
    }

    private void clearCartSafely(String userId) {
        currentStatus = "CLEARING_CART";
        try {
            cartActivity.clearCart(userId);
        } catch (Exception e) {
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .warn("Failed to clear cart for user {}, continuing", userId, e);
        }
    }

    private void compensatePayment(PaymentAuthResult paymentResult,
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
        runWithDlq(step, paymentId, String.valueOf(paymentAmountCents), () -> {
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

    private void safeRefundWallet(String userId, long amountCents) {
        if (amountCents <= 0) {
            return;
        }
        runWithDlq("refundWallet", userId, String.valueOf(amountCents), () ->
            compensationWalletActivity().refundWallet(
                userId,
                amountCents,
                paymentKeys.walletRefund(),
                "Saga wallet refund for checkout " + workflowId));
    }

    private void safeReleaseStock(String reservationId) {
        runWithDlq("releaseStock", reservationId, null, () ->
            compensationInventoryActivity.releaseStock(reservationId));
    }

    private void safeCancelOrder(String orderId) {
        runWithDlq("cancelOrder", orderId, null, () ->
            compensationOrderActivity.cancelOrder(orderId));
    }

    private void safeRollbackCoupon(String couponCode, String userId, String orderId) {
        boolean hasOrder = orderId != null && !orderId.isBlank();
        String step = hasOrder ? "unredeemCoupon" : "rollbackUsage";
        String targetId = hasOrder ? couponCode + ":" + userId + ":" + orderId : couponCode + ":" + userId;
        runWithDlq(step, targetId, null, () -> {
            if (hasOrder) {
                compensationCouponActivity.unredeemCoupon(couponCode, userId, orderId);
            } else {
                compensationCouponActivity.rollbackUsage(couponCode, userId);
            }
        });
    }

    private void runWithDlq(String step, String targetId, String payload, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to compensate step={} targetId={}, routing to DLQ: {}", step, targetId, e.getMessage());
            dlqActivity.recordFailedCompensation(workflowId, step, targetId, e.getMessage(), payload);
        }
    }

    private String sanitizeClientReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        String sanitized = reason.replaceAll("[\\r\\n]+", " ").trim();
        return sanitized.length() > 160 ? fallback : sanitized;
    }
}
