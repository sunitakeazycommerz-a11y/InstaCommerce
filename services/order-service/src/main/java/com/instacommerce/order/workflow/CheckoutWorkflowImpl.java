package com.instacommerce.order.workflow;

import com.instacommerce.order.dto.request.CheckoutRequest;
import com.instacommerce.order.exception.InsufficientStockException;
import com.instacommerce.order.exception.PaymentDeclinedException;
import com.instacommerce.order.workflow.activities.CartActivities;
import com.instacommerce.order.workflow.activities.InventoryActivities;
import com.instacommerce.order.workflow.activities.OrderActivities;
import com.instacommerce.order.workflow.activities.PaymentActivities;
import com.instacommerce.order.workflow.model.CheckoutResult;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import com.instacommerce.order.workflow.model.PaymentResult;
import com.instacommerce.order.workflow.model.ReserveResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.UUID;

public class CheckoutWorkflowImpl implements CheckoutWorkflow {
    private String currentStatus = "STARTED";

    private final InventoryActivities inventory = Workflow.newActivityStub(
        InventoryActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .setDoNotRetry(InsufficientStockException.class.getName())
                .build())
            .build());

    private final PaymentActivities payment = Workflow.newActivityStub(
        PaymentActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(3)
                .setDoNotRetry(PaymentDeclinedException.class.getName())
                .build())
            .build());

    private final OrderActivities orders = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());

    private final CartActivities cart = Workflow.newActivityStub(
        CartActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build());

    @Override
    public CheckoutResult execute(CheckoutRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        try {
            currentStatus = "RESERVING_INVENTORY";
            ReserveResult reserveResult = inventory.reserveInventory(
                request.idempotencyKey(),
                request.storeId(),
                request.items());
            saga.addCompensation(inventory::cancelReservation, reserveResult.reservationId());

            currentStatus = "AUTHORIZING_PAYMENT";
            PaymentResult paymentResult = payment.authorizePayment(
                request.idempotencyKey(),
                request.totalCents(),
                request.currency(),
                "pay-" + request.idempotencyKey());
            saga.addCompensation(payment::voidPayment, paymentResult.paymentId());

            currentStatus = "CREATING_ORDER";
            String orderId = orders.createOrder(CreateOrderCommand.builder()
                .userId(request.userId())
                .storeId(request.storeId())
                .items(request.items())
                .subtotalCents(request.subtotalCents())
                .discountCents(request.discountCents())
                .totalCents(request.totalCents())
                .currency(request.currency())
                .couponCode(request.couponCode())
                .reservationId(toUuid(reserveResult.reservationId()))
                .paymentId(toUuid(paymentResult.paymentId()))
                .idempotencyKey(request.idempotencyKey())
                .deliveryAddress(request.deliveryAddress())
                .build());
            saga.addCompensation(orders::cancelOrder, orderId, "SAGA_ROLLBACK");

            currentStatus = "CONFIRMING_RESERVATION";
            inventory.confirmReservation(reserveResult.reservationId());

            currentStatus = "CAPTURING_PAYMENT";
            payment.capturePayment(paymentResult.paymentId());

            currentStatus = "CLEARING_CART";
            try {
                cart.clearCart(request.userId().toString());
            } catch (Exception e) {
                Workflow.getLogger(CheckoutWorkflowImpl.class)
                    .warn("Failed to clear cart, continuing", e);
            }

            currentStatus = "FINALIZING";
            orders.updateOrderStatus(orderId, "PLACED");

            currentStatus = "COMPLETED";
            return CheckoutResult.success(orderId);
        } catch (Exception e) {
            currentStatus = "COMPENSATING";
            saga.compensate();
            currentStatus = "FAILED";
            return CheckoutResult.failure(e.getMessage());
        }
    }

    @Override
    public String getStatus() {
        return currentStatus;
    }

    private UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
