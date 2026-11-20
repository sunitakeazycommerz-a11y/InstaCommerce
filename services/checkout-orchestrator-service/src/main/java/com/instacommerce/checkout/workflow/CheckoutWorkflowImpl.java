package com.instacommerce.checkout.workflow;

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
import com.instacommerce.checkout.workflow.activity.CartActivity;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.domain.PriceDriftEvaluator;
import com.instacommerce.checkout.dto.WalletDebitResult;
import com.instacommerce.checkout.workflow.activity.WalletActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.PricingActivity;
import com.instacommerce.checkout.workflow.activity.CompensationDlqActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class CheckoutWorkflowImpl implements CheckoutWorkflow {

    private String currentStatus = "STARTED";
    private CheckoutResponse lastResponse;
    private WalletActivity walletActivity;
    private WalletActivity compensationWalletActivity;

    private WalletActivity walletActivity() {
        if (walletActivity == null) {
            walletActivity = Workflow.newActivityStub(
                WalletActivity.class,
                ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumAttempts(3)
                        .setBackoffCoefficient(2.0)
                        .build())
                    .build());
        }
        return walletActivity;
    }

    private WalletActivity compensationWalletActivity() {
        if (compensationWalletActivity == null) {
            compensationWalletActivity = Workflow.newActivityStub(
                WalletActivity.class,
                ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(2))
                        .setMaximumAttempts(5)
                        .setBackoffCoefficient(2.0)
                        .build())
                    .build());
        }
        return compensationWalletActivity;
    }

    private final String workflowId = Workflow.getInfo().getWorkflowId();

    /** Workflow-stable payment idempotency keys — never derived from activityId. */
    private final PaymentOperationKeys paymentKeys = new PaymentOperationKeys(workflowId);

    private final CartActivity cartActivity = Workflow.newActivityStub(
        CartActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final PricingActivity pricingActivity = Workflow.newActivityStub(
        PricingActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final InventoryActivity inventoryActivity = Workflow.newActivityStub(
        InventoryActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .setDoNotRetry(
                    "com.instacommerce.checkout.exception.InsufficientStockException")
                .build())
            .build());

    private final PaymentActivity paymentActivity = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofSeconds(45))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .setDoNotRetry(
                    "com.instacommerce.checkout.exception.PaymentDeclinedException")
                .build())
            .build());

    private final OrderActivity orderActivity = Workflow.newActivityStub(
        OrderActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final CouponActivity couponActivity = Workflow.newActivityStub(
        CouponActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .setDoNotRetry(
                    "com.instacommerce.pricing.exception.InvalidCouponException",
                    "com.instacommerce.pricing.exception.CouponNotFoundException")
                .build())
            .build());

    private final InventoryActivity compensationInventoryActivity = Workflow.newActivityStub(
        InventoryActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(5)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final PaymentActivity compensationPaymentActivity = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(45))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(5)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final OrderActivity compensationOrderActivity = Workflow.newActivityStub(
        OrderActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(5)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final CouponActivity compensationCouponActivity = Workflow.newActivityStub(
        CouponActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(20))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(5)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final CompensationDlqActivity dlqActivity = Workflow.newActivityStub(
        CompensationDlqActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    @Override
    public CheckoutResponse checkout(CheckoutRequest request) {
        Saga.Options sagaOptions = new Saga.Options.Builder()
            .setParallelCompensation(false)
            .setContinueWithError(true)
            .build();
        Saga saga = new Saga(sagaOptions);
        PaymentAuthResult paymentResult = null;
        Long paymentAmountCents = null;
        boolean paymentAuthorized = false;
        boolean paymentCaptured = false;
        boolean paymentCaptureAttempted = false;

        try {
            // Step 1: Validate cart
            currentStatus = "VALIDATING_CART";
            CartValidationResult cartResult = cartActivity.validateCart(request.userId());
            if (!cartResult.valid() || cartResult.items() == null || cartResult.items().isEmpty()) {
                currentStatus = "FAILED";
                CheckoutResponse failed = CheckoutResponse.failed("Cart is empty or invalid");
                lastResponse = failed;
                return failed;
            }
            List<CartItem> items = cartResult.items();
            if (items.stream().anyMatch(i -> i == null || i.quantity() == null || i.quantity() <= 0)) {
                currentStatus = "FAILED";
                CheckoutResponse failed = CheckoutResponse.failed("Cart contains items with invalid or non-positive quantities");
                lastResponse = failed;
                return failed;
            }
            String storeId = cartResult.storeId();

            // Step 2: Calculate prices
            currentStatus = "CALCULATING_PRICES";
            PricingRequest pricingRequest = new PricingRequest(
                request.userId(), items, storeId, request.couponCode(), request.deliveryAddressId());
            PricingResult pricingResult = pricingActivity.calculatePrice(pricingRequest);
            if (pricingResult == null || pricingResult.totalCents() < 0) {
                currentStatus = "FAILED";
                CheckoutResponse failed = CheckoutResponse.failed("Pricing calculation failed: invalid total amount");
                lastResponse = failed;
                return failed;
            }
            PriceDriftEvaluator.Evaluation priceDrift = PriceDriftEvaluator.evaluate(
                pricingResult.totalCents(),
                request.expectedTotalCents(),
                request.maxPriceDriftCents()
            );
            if (!priceDrift.withinTolerance()) {
                currentStatus = "FAILED";
                CheckoutResponse failed = CheckoutResponse.failed(priceDrift.failureReason());
                lastResponse = failed;
                return failed;
            }
            long totalAmountCents = pricingResult.totalCents();
            String currency = (pricingResult.currency() != null && !pricingResult.currency().isBlank())
                ? pricingResult.currency()
                : PricingResult.DEFAULT_CURRENCY;
            UUID deterministicOrderId = UUID.nameUUIDFromBytes(
                ("order-" + workflowId).getBytes(StandardCharsets.UTF_8));
            String expectedOrderId = deterministicOrderId.toString();

            // Step 3: Reserve inventory — register compensation BEFORE the activity
            currentStatus = "RESERVING_INVENTORY";
            InventoryReservationResult reservationResult = inventoryActivity.reserveStock(
                new InventoryReserveRequest(workflowId + ":inventory:reserve", storeId, items));
            if (!reservationResult.reserved()) {
                currentStatus = "FAILED";
                CheckoutResponse failed = CheckoutResponse.failed("Some items are out of stock");
                lastResponse = failed;
                return failed;
            }
            saga.addCompensation(() -> safeReleaseStock(reservationResult.reservationId()));

            // Step 3.5: Split-tender wallet debit (optional)
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
                    currentStatus = "FAILED";
                    String reason = (walletResult != null && walletResult.failureReason() != null
                        && !walletResult.failureReason().isBlank())
                        ? walletResult.failureReason()
                        : "Wallet debit declined";
                    CheckoutResponse failed = CheckoutResponse.failed("Wallet debit failed: " + reason);
                    lastResponse = failed;
                    return failed;
                }
                final long appliedWalletCents = walletResult.amountCents() > 0
                    ? Math.min(walletResult.amountCents(), totalAmountCents)
                    : requestedWalletCents;
                walletDebitedCents = appliedWalletCents;
                saga.addCompensation(() -> safeRefundWallet(request.userId(), appliedWalletCents));
            }
            paymentAmountCents = totalAmountCents - walletDebitedCents;

            // Step 4: Authorize PSP payment for remaining split-tender balance
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
                    currentStatus = "FAILED";
                    if ("TOKEN_EXPIRED".equalsIgnoreCase(paymentResult.declineReason())) {
                        CheckoutResponse expired = new CheckoutResponse(
                            null,
                            "PAYMENT_TOKEN_EXPIRED: Client re-authentication required",
                            0,
                            0
                        );
                        lastResponse = expired;
                        return expired;
                    }
                    CheckoutResponse declined = CheckoutResponse.failed(
                        "Payment declined: " + paymentResult.declineReason());
                    lastResponse = declined;
                    return declined;
                }
            }

            // Step 5: Create order
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
                .couponCode(request.couponCode())
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
                .build();
            OrderCreationResult orderResult = orderActivity.createOrder(orderRequest);
            saga.addCompensation(() -> safeCancelOrder(orderResult.orderId()));

            // Step 5.5: Redeem coupon (if applicable)
            if (request.couponCode() != null && !request.couponCode().isBlank()
                    && pricingResult.discountCents() > 0) {
                currentStatus = "REDEEMING_COUPON";
                couponActivity.redeemCoupon(
                    request.couponCode(),
                    request.userId(),
                    orderResult.orderId(),
                    pricingResult.discountCents());
                saga.addCompensation(() -> safeRollbackCoupon(
                    request.couponCode(), request.userId(), orderResult.orderId()));
            }

            // Step 6: Confirm inventory reservation + capture payment
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

            // Step 7: Clear cart (best effort — don't fail checkout if this fails)
            currentStatus = "CLEARING_CART";
            try {
                cartActivity.clearCart(request.userId());
            } catch (Exception e) {
                Workflow.getLogger(CheckoutWorkflowImpl.class)
                    .warn("Failed to clear cart for user {}, continuing", request.userId(), e);
            }

            currentStatus = "COMPLETED";
            CheckoutResponse completed = CheckoutResponse.success(
                orderResult.orderId(),
                pricingResult.totalCents(),
                orderResult.estimatedDeliveryMinutes());
            lastResponse = completed;
            return completed;

        } catch (Exception e) {
            currentStatus = "COMPENSATING";
            compensatePayment(
                paymentResult,
                paymentAuthorized,
                paymentCaptured,
                paymentCaptureAttempted,
                paymentAmountCents);
            saga.compensate();
            currentStatus = "FAILED";
            CheckoutResponse failed = CheckoutResponse.failed(e.getMessage());
            lastResponse = failed;
            return failed;
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
        try {
            String paymentId = paymentResult.paymentId();
            String refundIdempotencyKey = paymentKeys.refund(paymentId);
            String voidIdempotencyKey = paymentKeys.voidOp(paymentId);
            if (paymentCaptured) {
                compensationPaymentActivity.refundPayment(paymentId, paymentAmountCents, refundIdempotencyKey);
                return;
            }
            try {
                compensationPaymentActivity.voidPayment(paymentId, voidIdempotencyKey);
                return;
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
        } catch (Exception compensationFailure) {
            String workflowId = Workflow.getInfo().getWorkflowId();
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to compensate payment {}, routing to DLQ: {}", paymentResult.paymentId(), compensationFailure.getMessage());
            dlqActivity.recordFailedCompensation(
                workflowId,
                paymentCaptured ? "refundPayment" : "voidPayment",
                paymentResult.paymentId(),
                compensationFailure.getMessage(),
                String.valueOf(paymentAmountCents)
            );
        }
    }

    private void safeRefundWallet(String userId, long amountCents) {
        if (amountCents <= 0) {
            return;
        }
        try {
            compensationWalletActivity().refundWallet(
                userId,
                amountCents,
                paymentKeys.walletRefund(),
                "Saga wallet refund for checkout " + workflowId);
        } catch (Exception e) {
            String workflowId = Workflow.getInfo().getWorkflowId();
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to refund wallet for user {}, routing to DLQ: {}", userId, e.getMessage());
            dlqActivity.recordFailedCompensation(
                workflowId,
                "refundWallet",
                userId,
                e.getMessage(),
                String.valueOf(amountCents)
            );
        }
    }

    private void safeReleaseStock(String reservationId) {
        try {
            compensationInventoryActivity.releaseStock(reservationId);
        } catch (Exception e) {
            String workflowId = Workflow.getInfo().getWorkflowId();
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to release stock for reservation {}, routing to DLQ: {}", reservationId, e.getMessage());
            dlqActivity.recordFailedCompensation(workflowId, "releaseStock", reservationId, e.getMessage(), null);
        }
    }

    private void safeCancelOrder(String orderId) {
        try {
            compensationOrderActivity.cancelOrder(orderId);
        } catch (Exception e) {
            String workflowId = Workflow.getInfo().getWorkflowId();
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to cancel order {}, routing to DLQ: {}", orderId, e.getMessage());
            dlqActivity.recordFailedCompensation(workflowId, "cancelOrder", orderId, e.getMessage(), null);
        }
    }

    private void safeRollbackCoupon(String couponCode, String userId, String orderId) {
        try {
            if (orderId != null && !orderId.isBlank()) {
                compensationCouponActivity.unredeemCoupon(couponCode, userId, orderId);
            } else {
                compensationCouponActivity.rollbackUsage(couponCode, userId);
            }
        } catch (Exception e) {
            String workflowId = Workflow.getInfo().getWorkflowId();
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .error("Failed to rollback coupon {} for user {}, routing to DLQ: {}", couponCode, userId, e.getMessage());
            String step = (orderId != null && !orderId.isBlank()) ? "unredeemCoupon" : "rollbackUsage";
            String targetId = (orderId != null && !orderId.isBlank())
                ? couponCode + ":" + userId + ":" + orderId
                : couponCode + ":" + userId;
            dlqActivity.recordFailedCompensation(workflowId, step, targetId, e.getMessage(), null);
        }
    }
}
