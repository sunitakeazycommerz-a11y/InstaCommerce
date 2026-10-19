package com.instacommerce.checkout.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import com.instacommerce.checkout.workflow.CheckoutWorkflowImpl;
import com.instacommerce.checkout.workflow.activity.CartActivity;
import com.instacommerce.checkout.workflow.activity.CompensationDlqActivity;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.PricingActivity;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * End-to-end integration test suite using Temporal {@link TestWorkflowEnvironment} to exercise
 * {@link CheckoutWorkflowImpl} orchestration and saga compensation execution under failure modes.
 */
class CheckoutSagaCompensationIntegrationTest {

    private static final String TASK_QUEUE = "CHECKOUT_TEST_TASK_QUEUE";
    private static final String USER_ID = "usr-integration-42";
    private static final String PAYMENT_METHOD_ID = "pm_card_visa";
    private static final String DELIVERY_ADDRESS_ID = "addr_mumbai_101";
    private static final String STORE_ID = "store_powai_1";
    private static final String RESERVATION_ID = "res-saga-777";
    private static final String PAYMENT_ID = "pay-saga-888";
    private static final String ORDER_ID = "ord-saga-999";

    private TestWorkflowEnvironment testEnv;
    private CartActivity cartActivity;
    private PricingActivity pricingActivity;
    private InventoryActivity inventoryActivity;
    private PaymentActivity paymentActivity;
    private OrderActivity orderActivity;
    private CouponActivity couponActivity;
    private CompensationDlqActivity dlqActivity;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();

        cartActivity = mock(CartActivity.class);
        pricingActivity = mock(PricingActivity.class);
        inventoryActivity = mock(InventoryActivity.class);
        paymentActivity = mock(PaymentActivity.class);
        orderActivity = mock(OrderActivity.class);
        couponActivity = mock(CouponActivity.class);
        dlqActivity = mock(CompensationDlqActivity.class);

        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            new TestCartActivity(cartActivity),
            new TestPricingActivity(pricingActivity),
            new TestInventoryActivity(inventoryActivity),
            new TestPaymentActivity(paymentActivity),
            new TestOrderActivity(orderActivity),
            new TestCouponActivity(couponActivity),
            new TestCompensationDlqActivity(dlqActivity)
        );

        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    private CheckoutWorkflow createWorkflowStub(String workflowId) {
        return testEnv.getWorkflowClient().newWorkflowStub(
            CheckoutWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build()
        );
    }

    @Test
    @DisplayName("Happy path: full checkout orchestration executes forward and completes successfully")
    void shouldExecuteHappyPathSaga() {
        CheckoutWorkflow workflow = createWorkflowStub("test-checkout-happy-" + System.currentTimeMillis());

        List<CartItem> cartItems = List.of(
            new CartItem("item-milk-1", 2, 5000L),
            new CartItem("item-bread-1", 1, 3000L)
        );

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(13000L, 0L, 2000L, 15000L, "INR", null, null));
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));
        when(paymentActivity.authorizePayment(eq(15000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));
        when(orderActivity.createOrder(any()))
            .thenReturn(new OrderCreationResult(ORDER_ID, 15));

        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);
        CheckoutResponse response = workflow.checkout(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.totalCents()).isEqualTo(15000L);
        assertThat(response.estimatedDeliveryMinutes()).isEqualTo(15);

        // Verify confirmStock and capturePayment called
        verify(inventoryActivity).confirmStock(RESERVATION_ID);
        verify(paymentActivity).capturePayment(eq(PAYMENT_ID), anyString());
        verify(cartActivity).clearCart(USER_ID);

        // Verify no compensations were executed
        verify(inventoryActivity, never()).releaseStock(anyString());
        verify(paymentActivity, never()).voidPayment(anyString(), anyString());
        verify(paymentActivity, never()).refundPayment(anyString(), anyLong(), anyString());
        verify(orderActivity, never()).cancelOrder(anyString());
    }

    @Test
    @DisplayName("Payment declined: inventory reservation is compensated via releaseStock")
    void shouldCompensateInventoryWhenPaymentDeclined() {
        CheckoutWorkflow workflow = createWorkflowStub("test-checkout-declined-" + System.currentTimeMillis());

        List<CartItem> cartItems = List.of(new CartItem("item-coffee-1", 1, 45000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(45000L, 0L, 0L, 45000L, "INR", null, null));
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));
        when(paymentActivity.authorizePayment(eq(45000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(null, false, "INSUFFICIENT_FUNDS"));

        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);
        CheckoutResponse response = workflow.checkout(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).startsWith("FAILED:");
        assertThat(response.status()).contains("Payment declined: INSUFFICIENT_FUNDS");

        // Assert compensating activity was executed for inventory reservation
        verify(inventoryActivity).releaseStock(RESERVATION_ID);

        // Assert forward operations after payment did not execute
        verify(orderActivity, never()).createOrder(any());
        verify(inventoryActivity, never()).confirmStock(anyString());
        verify(paymentActivity, never()).capturePayment(anyString(), anyString());
    }

    @Test
    @DisplayName("Order creation failure: payment is voided and inventory reservation is released in reverse order")
    void shouldCompensatePaymentAndInventoryWhenOrderCreationFails() {
        CheckoutWorkflow workflow = createWorkflowStub("test-checkout-order-failed-" + System.currentTimeMillis());

        List<CartItem> cartItems = List.of(new CartItem("item-apple-1", 3, 6000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(18000L, 0L, 0L, 18000L, "INR", null, null));
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));
        when(paymentActivity.authorizePayment(eq(18000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));
        when(orderActivity.createOrder(any()))
            .thenThrow(new RuntimeException("Order database deadlock connection error"));

        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);
        CheckoutResponse response = workflow.checkout(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).startsWith("FAILED");

        // Verify reverse compensation order: void payment first, then release stock
        InOrder inOrder = inOrder(paymentActivity, inventoryActivity);
        inOrder.verify(paymentActivity).voidPayment(eq(PAYMENT_ID), anyString());
        inOrder.verify(inventoryActivity).releaseStock(RESERVATION_ID);
    }

    @Test
    @DisplayName("Coupon redemption failure: order is cancelled, payment is voided, and inventory is released")
    void shouldCompensateAllActivitiesWhenCouponRedemptionFails() {
        CheckoutWorkflow workflow = createWorkflowStub("test-checkout-coupon-failed-" + System.currentTimeMillis());

        List<CartItem> cartItems = List.of(new CartItem("item-snack-1", 2, 2000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(4000L, 500L, 0L, 3500L, "INR", null, null));
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));
        when(paymentActivity.authorizePayment(eq(3500L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));
        when(orderActivity.createOrder(any()))
            .thenReturn(new OrderCreationResult(ORDER_ID, 12));
        doThrow(new RuntimeException("Coupon already depleted"))
            .when(couponActivity).redeemCoupon(eq("SAVE500"), eq(USER_ID), eq(ORDER_ID), eq(500L));

        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, "SAVE500", DELIVERY_ADDRESS_ID);
        CheckoutResponse response = workflow.checkout(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).startsWith("FAILED");

        // Verify compensation occurred: void payment, cancel order, release inventory
        verify(paymentActivity).voidPayment(eq(PAYMENT_ID), anyString());
        verify(orderActivity).cancelOrder(ORDER_ID);
        verify(inventoryActivity).releaseStock(RESERVATION_ID);
    }

    @Test
    @DisplayName("Compensation DLQ: failure during releaseStock logs event to compensation DLQ")
    void shouldRouteToDlqWhenInventoryCompensationThrows() {
        CheckoutWorkflow workflow = createWorkflowStub("test-checkout-dlq-" + System.currentTimeMillis());

        List<CartItem> cartItems = List.of(new CartItem("item-rice-1", 1, 12000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(12000L, 0L, 0L, 12000L, "INR", null, null));
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));
        when(paymentActivity.authorizePayment(eq(12000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(null, false, "CARD_EXPIRED"));
        doThrow(new RuntimeException("Inventory service connection refused"))
            .when(inventoryActivity).releaseStock(RESERVATION_ID);

        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);
        CheckoutResponse response = workflow.checkout(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).startsWith("FAILED");

        // Verify DLQ activity received the failure record
        verify(dlqActivity).recordFailedCompensation(
            anyString(),
            eq("releaseStock"),
            eq(RESERVATION_ID),
            anyString(),
            eq(null)
        );
    }

    // --- Activity Implementations without @ActivityMethod annotations on implementation classes ---

    static class TestCartActivity implements CartActivity {
        private final CartActivity mock;
        TestCartActivity(CartActivity mock) { this.mock = mock; }
        @Override public CartValidationResult validateCart(String userId) { return mock.validateCart(userId); }
        @Override public void clearCart(String userId) { mock.clearCart(userId); }
    }

    static class TestPricingActivity implements PricingActivity {
        private final PricingActivity mock;
        TestPricingActivity(PricingActivity mock) { this.mock = mock; }
        @Override public PricingResult calculatePrice(PricingRequest request) { return mock.calculatePrice(request); }
    }

    static class TestInventoryActivity implements InventoryActivity {
        private final InventoryActivity mock;
        TestInventoryActivity(InventoryActivity mock) { this.mock = mock; }
        @Override public InventoryReservationResult reserveStock(List<CartItem> items) { return mock.reserveStock(items); }
        @Override public void confirmStock(String reservationId) { mock.confirmStock(reservationId); }
        @Override public void releaseStock(String reservationId) { mock.releaseStock(reservationId); }
    }

    static class TestPaymentActivity implements PaymentActivity {
        private final PaymentActivity mock;
        TestPaymentActivity(PaymentActivity mock) { this.mock = mock; }
        @Override public PaymentAuthResult authorizePayment(long amountCents, String paymentMethodId, String idempotencyKey) {
            return mock.authorizePayment(amountCents, paymentMethodId, idempotencyKey);
        }
        @Override public void capturePayment(String paymentId, String idempotencyKey) { mock.capturePayment(paymentId, idempotencyKey); }
        @Override public void voidPayment(String paymentId, String idempotencyKey) { mock.voidPayment(paymentId, idempotencyKey); }
        @Override public void refundPayment(String paymentId, long amountCents, String idempotencyKey) { mock.refundPayment(paymentId, amountCents, idempotencyKey); }
    }

    static class TestOrderActivity implements OrderActivity {
        private final OrderActivity mock;
        TestOrderActivity(OrderActivity mock) { this.mock = mock; }
        @Override public OrderCreationResult createOrder(OrderCreateRequest request) { return mock.createOrder(request); }
        @Override public void cancelOrder(String orderId) { mock.cancelOrder(orderId); }
    }

    static class TestCouponActivity implements CouponActivity {
        private final CouponActivity mock;
        TestCouponActivity(CouponActivity mock) { this.mock = mock; }
        @Override public void redeemCoupon(String couponCode, String userId, String orderId, long discountCents) {
            mock.redeemCoupon(couponCode, userId, orderId, discountCents);
        }
        @Override public void unredeemCoupon(String code, String userId, String orderId) {
            mock.unredeemCoupon(code, userId, orderId);
        }
        @Override public void rollbackUsage(String couponCode, String userId) { mock.rollbackUsage(couponCode, userId); }
    }

    static class TestCompensationDlqActivity implements CompensationDlqActivity {
        private final CompensationDlqActivity mock;
        TestCompensationDlqActivity(CompensationDlqActivity mock) { this.mock = mock; }
        @Override public void recordFailedCompensation(String workflowId, String step, String referenceId, String reason, String payload) {
            mock.recordFailedCompensation(workflowId, step, referenceId, reason, payload);
        }
    }
}
