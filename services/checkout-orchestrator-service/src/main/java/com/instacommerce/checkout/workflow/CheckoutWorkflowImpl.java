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

            // Step 3: Reserve inventory — register compensation BEFORE the activity
            currentStatus = "RESERVING_INVENTORY";
            InventoryReservationResult reservationResult = inventoryActivity.reserveStock(items);
            saga.addCompensation(inventoryActivity::releaseStock, reservationResult.reservationId());

            // Step 4: Authorize payment with idempotency key to prevent retry double-charges
            currentStatus = "AUTHORIZING_PAYMENT";
            String paymentIdempotencyKey = Workflow.getInfo().getWorkflowId() + "-payment";
            PaymentAuthResult paymentResult = paymentActivity.authorizePayment(
                pricingResult.totalCents(), request.paymentMethodId(), paymentIdempotencyKey);

            // Register payment void compensation immediately after successful authorization
            saga.addCompensation(paymentActivity::voidPayment, paymentResult.paymentId());

            if (!paymentResult.authorized()) {
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
            paymentActivity.capturePayment(paymentResult.paymentId());

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
            saga.compensate();
            currentStatus = "FAILED";
            return CheckoutResponse.failed(e.getMessage());
        }
    }

    @Override
    public String getStatus() {
        return currentStatus;
    }
}
