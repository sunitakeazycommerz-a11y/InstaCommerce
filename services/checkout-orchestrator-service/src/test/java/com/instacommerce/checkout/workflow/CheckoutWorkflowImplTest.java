package com.instacommerce.checkout.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.CartValidationResult;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.dto.PaymentAuthResult;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.workflow.activity.CartActivity;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.PricingActivity;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

/**
 * Unit tests for {@link CheckoutWorkflowImpl} verifying the checkout saga orchestration logic.
 * Uses Mockito to mock Temporal activity stubs and verify call ordering and compensation behavior.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutWorkflowImplTest {

    private static final String WORKFLOW_ID = "checkout-user42-ik-test123";
    private static final String USER_ID = "user-42";
    private static final String PAYMENT_METHOD_ID = "pm-card-1";
    private static final String DELIVERY_ADDRESS_ID = "addr-1";
    private static final String STORE_ID = "store-mumbai-1";
    private static final String PAYMENT_ID = "pay-100";
    private static final String RESERVATION_ID = "res-200";
    private static final String ORDER_ID = "order-300";

    @Mock private CartActivity cartActivity;
    @Mock private PricingActivity pricingActivity;
    @Mock private InventoryActivity inventoryActivity;
    @Mock private PaymentActivity paymentActivity;
    @Mock private OrderActivity orderActivity;
    @Mock private CouponActivity couponActivity;

    private MockedStatic<Workflow> workflowMock;
    private CheckoutWorkflowImpl workflow;

    @BeforeEach
    void setUp() {
        workflowMock = mockStatic(Workflow.class);

        // Mock WorkflowInfo for PaymentOperationKeys initialization
        WorkflowInfo workflowInfo = mock(WorkflowInfo.class);
        when(workflowInfo.getWorkflowId()).thenReturn(WORKFLOW_ID);
        workflowMock.when(Workflow::getInfo).thenReturn(workflowInfo);

        // Mock activity stub creation to return our Mockito mocks
        workflowMock.when(() -> Workflow.newActivityStub(eq(CartActivity.class), any()))
            .thenReturn(cartActivity);
        workflowMock.when(() -> Workflow.newActivityStub(eq(PricingActivity.class), any()))
            .thenReturn(pricingActivity);
        workflowMock.when(() -> Workflow.newActivityStub(eq(InventoryActivity.class), any()))
            .thenReturn(inventoryActivity);
        workflowMock.when(() -> Workflow.newActivityStub(eq(PaymentActivity.class), any()))
            .thenReturn(paymentActivity);
        workflowMock.when(() -> Workflow.newActivityStub(eq(OrderActivity.class), any()))
            .thenReturn(orderActivity);
        workflowMock.when(() -> Workflow.newActivityStub(eq(CouponActivity.class), any()))
            .thenReturn(couponActivity);

        // Mock Workflow.getLogger used for warn-level logging in error paths
        Logger mockLogger = mock(Logger.class);
        workflowMock.when(() -> Workflow.getLogger(any(Class.class))).thenReturn(mockLogger);

        workflow = new CheckoutWorkflowImpl();
    }

    @AfterEach
    void tearDown() {
        workflowMock.close();
    }

    // -- shouldCompleteCheckoutHappyPath -----------------------------------------

    @Test
    void shouldCompleteCheckoutHappyPath() {
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);

        List<CartItem> cartItems = List.of(
            new CartItem("prod-1", 2, 5000L),
            new CartItem("prod-2", 1, 5000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));

        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(15000L, 0L, 0L, 15000L, "INR", null, null));

        when(inventoryActivity.reserveStock(cartItems))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));

        when(paymentActivity.authorizePayment(eq(15000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));

        when(orderActivity.createOrder(any()))
            .thenReturn(new OrderCreationResult(ORDER_ID, 15));

        CheckoutResponse response = workflow.checkout(request);

        // Verify successful response
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.totalCents()).isEqualTo(15000L);
        assertThat(response.estimatedDeliveryMinutes()).isEqualTo(15);

        // Verify activities were called in the correct order
        InOrder inOrder = inOrder(cartActivity, pricingActivity, inventoryActivity,
            paymentActivity, orderActivity);
        inOrder.verify(cartActivity).validateCart(USER_ID);
        inOrder.verify(pricingActivity).calculatePrice(any());
        inOrder.verify(inventoryActivity).reserveStock(cartItems);
        inOrder.verify(paymentActivity).authorizePayment(eq(15000L), eq(PAYMENT_METHOD_ID), anyString());
        inOrder.verify(orderActivity).createOrder(any());
        inOrder.verify(inventoryActivity).confirmStock(RESERVATION_ID);
        inOrder.verify(paymentActivity).capturePayment(eq(PAYMENT_ID), anyString());

        // Cart clear is best-effort but should be called
        verify(cartActivity).clearCart(USER_ID);

        // No coupon was provided, so coupon activity should not be called
        verify(couponActivity, never()).redeemCoupon(any(), any(), any(), anyLong());
    }

    @Test
    void shouldCompleteCheckoutWithCouponRedemption() {
        String couponCode = "SAVE20";
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, couponCode, DELIVERY_ADDRESS_ID);

        List<CartItem> cartItems = List.of(new CartItem("prod-1", 1, 10000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));

        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(10000L, 2000L, 0L, 8000L, "INR", "quote-1", "token-1"));

        when(inventoryActivity.reserveStock(cartItems))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));

        when(paymentActivity.authorizePayment(eq(8000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));

        when(orderActivity.createOrder(any()))
            .thenReturn(new OrderCreationResult(ORDER_ID, 12));

        CheckoutResponse response = workflow.checkout(request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.totalCents()).isEqualTo(8000L);

        // Coupon should be redeemed since couponCode is present and discount > 0
        verify(couponActivity).redeemCoupon(couponCode, USER_ID, ORDER_ID, 2000L);
    }

    // -- shouldCompensateOnPaymentFailure ----------------------------------------

    @Test
    void shouldCompensateOnPaymentFailure() {
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);

        List<CartItem> cartItems = List.of(new CartItem("prod-1", 2, 5000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));

        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(10000L, 0L, 0L, 10000L, "INR", null, null));

        when(inventoryActivity.reserveStock(cartItems))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));

        // Payment authorization fails
        when(paymentActivity.authorizePayment(eq(10000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(null, false, "Insufficient funds"));

        CheckoutResponse response = workflow.checkout(request);

        // Verify failed response with decline reason
        assertThat(response.status()).startsWith("FAILED");
        assertThat(response.status()).contains("Insufficient funds");
        assertThat(response.orderId()).isNull();

        // Inventory reservation should be released via saga compensation
        verify(inventoryActivity).releaseStock(RESERVATION_ID);

        // Order should never be created since payment failed
        verify(orderActivity, never()).createOrder(any());

        // No payment void/refund needed since authorization itself failed
        verify(paymentActivity, never()).voidPayment(any(), any());
        verify(paymentActivity, never()).refundPayment(any(), anyLong(), any());

        // Cart should NOT be cleared on failure
        verify(cartActivity, never()).clearCart(any());
    }

    @Test
    void shouldCompensateOnOrderCreationFailure() {
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);

        List<CartItem> cartItems = List.of(new CartItem("prod-1", 1, 5000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));

        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(5000L, 0L, 0L, 5000L, "INR", null, null));

        when(inventoryActivity.reserveStock(cartItems))
            .thenReturn(new InventoryReservationResult(RESERVATION_ID, true));

        when(paymentActivity.authorizePayment(eq(5000L), eq(PAYMENT_METHOD_ID), anyString()))
            .thenReturn(new PaymentAuthResult(PAYMENT_ID, true, null));

        // Order creation throws an exception
        when(orderActivity.createOrder(any()))
            .thenThrow(new RuntimeException("Order service unavailable"));

        CheckoutResponse response = workflow.checkout(request);

        // Verify failed response
        assertThat(response.status()).startsWith("FAILED");

        // Payment was authorized but NOT captured, so compensatePayment should void it
        verify(paymentActivity).voidPayment(eq(PAYMENT_ID), anyString());

        // Inventory should be released via saga compensation
        verify(inventoryActivity).releaseStock(RESERVATION_ID);
    }

    @Test
    void shouldFailFastOnEmptyCart() {
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, List.of(), false, null));

        CheckoutResponse response = workflow.checkout(request);

        assertThat(response.status()).startsWith("FAILED");
        assertThat(response.status()).contains("Cart is empty or invalid");

        // Nothing else should be called after cart validation fails
        verify(pricingActivity, never()).calculatePrice(any());
        verify(inventoryActivity, never()).reserveStock(any());
        verify(paymentActivity, never()).authorizePayment(anyLong(), any(), any());
        verify(orderActivity, never()).createOrder(any());
    }

    @Test
    void shouldFailOnOutOfStock() {
        CheckoutRequest request = new CheckoutRequest(USER_ID, PAYMENT_METHOD_ID, null, DELIVERY_ADDRESS_ID);

        List<CartItem> cartItems = List.of(new CartItem("prod-1", 1, 5000L));

        when(cartActivity.validateCart(USER_ID))
            .thenReturn(new CartValidationResult(USER_ID, cartItems, true, STORE_ID));

        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(5000L, 0L, 0L, 5000L, "INR", null, null));

        // Stock reservation fails
        when(inventoryActivity.reserveStock(cartItems))
            .thenReturn(new InventoryReservationResult(null, false));

        CheckoutResponse response = workflow.checkout(request);

        assertThat(response.status()).startsWith("FAILED");
        assertThat(response.status()).contains("out of stock");

        // No payment or order should be attempted
        verify(paymentActivity, never()).authorizePayment(anyLong(), any(), any());
        verify(orderActivity, never()).createOrder(any());

        // No inventory release needed since reservation was never made
        verify(inventoryActivity, never()).releaseStock(any());
    }
}
