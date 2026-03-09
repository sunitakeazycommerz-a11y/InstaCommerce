package com.instacommerce.checkout.workflow;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.CartValidationResult;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.dto.PaymentAuthResult;
import com.instacommerce.checkout.dto.PricingRequest;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.workflow.activity.CartActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.PricingActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;

public class CheckoutWorkflowImpl implements CheckoutWorkflow {

    private String currentStatus = "STARTED";

    /** Workflow-stable payment idempotency keys — never derived from activityId. */
    private final PaymentOperationKeys paymentKeys =
        new PaymentOperationKeys(Workflow.getInfo().getWorkflowId());

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
            if (!cartResult.valid()) {
                return CheckoutResponse.failed("Cart is empty or invalid");
            }
            List<CartItem> items = cartResult.items();
            String storeId = cartResult.storeId();

            // Step 2: Calculate prices
            currentStatus = "CALCULATING_PRICES";
            PricingRequest pricingRequest = new PricingRequest(
                request.userId(), items, storeId, request.couponCode(), request.deliveryAddressId());
            PricingResult pricingResult = pricingActivity.calculatePrice(pricingRequest);
            paymentAmountCents = pricingResult.totalCents();

            // Step 3: Reserve inventory — register compensation BEFORE the activity
            currentStatus = "RESERVING_INVENTORY";
            InventoryReservationResult reservationResult = inventoryActivity.reserveStock(items);
            if (!reservationResult.reserved()) {
                currentStatus = "FAILED";
                return CheckoutResponse.failed("Some items are out of stock");
            }
            saga.addCompensation(inventoryActivity::releaseStock, reservationResult.reservationId());

            // Step 4: Authorize payment with workflow-stable idempotency key
            currentStatus = "AUTHORIZING_PAYMENT";
            paymentResult = paymentActivity.authorizePayment(
                paymentAmountCents, request.paymentMethodId(), paymentKeys.authorize());
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
                return CheckoutResponse.failed(
                    "Payment declined: " + paymentResult.declineReason());
            }

            // Step 5: Create order
            currentStatus = "CREATING_ORDER";
            OrderCreateRequest orderRequest = new OrderCreateRequest(
                request.userId(),
                storeId,
                items,
                pricingResult.subtotalCents(),
                pricingResult.discountCents(),
                pricingResult.deliveryFeeCents(),
                pricingResult.totalCents(),
                pricingResult.currency(),
                request.couponCode(),
                reservationResult.reservationId(),
                paymentResult.paymentId(),
                request.deliveryAddressId(),
                request.paymentMethodId()
            );
            OrderCreationResult orderResult = orderActivity.createOrder(orderRequest);
            saga.addCompensation(orderActivity::cancelOrder, orderResult.orderId());

            // Step 6: Confirm inventory reservation + capture payment
            currentStatus = "CONFIRMING";
            inventoryActivity.confirmStock(reservationResult.reservationId());
            paymentCaptureAttempted = true;
            paymentActivity.capturePayment(
                paymentResult.paymentId(),
                paymentKeys.capture(paymentResult.paymentId()));
            paymentCaptured = true;

            // Step 7: Clear cart (best effort — don't fail checkout if this fails)
            currentStatus = "CLEARING_CART";
            try {
                cartActivity.clearCart(request.userId());
            } catch (Exception e) {
                Workflow.getLogger(CheckoutWorkflowImpl.class)
                    .warn("Failed to clear cart for user {}, continuing", request.userId(), e);
            }

            currentStatus = "COMPLETED";
            return CheckoutResponse.success(
                orderResult.orderId(),
                pricingResult.totalCents(),
                orderResult.estimatedDeliveryMinutes());

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
            return CheckoutResponse.failed(e.getMessage());
        }
    }

    @Override
    public String getStatus() {
        return currentStatus;
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
                paymentActivity.refundPayment(paymentId, paymentAmountCents, refundIdempotencyKey);
                return;
            }
            if (paymentCaptureAttempted) {
                try {
                    paymentActivity.refundPayment(paymentId, paymentAmountCents, refundIdempotencyKey);
                    return;
                } catch (Exception refundFailure) {
                    Workflow.getLogger(CheckoutWorkflowImpl.class)
                        .warn("Refund failed for payment {}, attempting void", paymentId, refundFailure);
                }
            }
            paymentActivity.voidPayment(paymentId, voidIdempotencyKey);
        } catch (Exception compensationFailure) {
            Workflow.getLogger(CheckoutWorkflowImpl.class)
                .warn("Failed to compensate payment {}", paymentResult.paymentId(), compensationFailure);
        }
    }
}
