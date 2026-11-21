package com.instacommerce.checkout.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.CartValidationResult;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.dto.PaymentAuthResult;
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
import io.temporal.workflow.Async;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.helpers.NOPLogger;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutSplitTenderAndDriftTest {

    private static final String WORKFLOW_ID = "checkout-user-1-ik-split";

    @Mock
    private CartActivity cartActivity;
    @Mock
    private PricingActivity pricingActivity;
    @Mock
    private InventoryActivity inventoryActivity;
    @Mock
    private PaymentActivity paymentActivity;
    @Mock
    private OrderActivity orderActivity;
    @Mock
    private CouponActivity couponActivity;
    @Mock
    private WalletActivity walletActivity;
    @Mock
    private CompensationDlqActivity dlqActivity;

    private CheckoutWorkflowImpl workflow;

    @BeforeEach
    void setUp() {
        WorkflowInfo workflowInfo = mock(WorkflowInfo.class);
        when(workflowInfo.getWorkflowId()).thenReturn(WORKFLOW_ID);

        try (MockedStatic<Workflow> workflowStatic = mockStatic(Workflow.class)) {
            workflowStatic.when(Workflow::getInfo).thenReturn(workflowInfo);
            workflowStatic.when(() -> Workflow.getLogger(any(Class.class))).thenReturn(NOPLogger.NOP_LOGGER);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(CartActivity.class), any(ActivityOptions.class)))
                .thenReturn(cartActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(PricingActivity.class), any(ActivityOptions.class)))
                .thenReturn(pricingActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(InventoryActivity.class), any(ActivityOptions.class)))
                .thenReturn(inventoryActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(PaymentActivity.class), any(ActivityOptions.class)))
                .thenReturn(paymentActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(OrderActivity.class), any(ActivityOptions.class)))
                .thenReturn(orderActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(CouponActivity.class), any(ActivityOptions.class)))
                .thenReturn(couponActivity);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(CompensationDlqActivity.class), any(ActivityOptions.class)))
                .thenReturn(dlqActivity);

            workflow = new CheckoutWorkflowImpl();
        }
    }

    private CheckoutResponse executeWorkflow(CheckoutRequest request) {
        WorkflowInfo workflowInfo = mock(WorkflowInfo.class);
        when(workflowInfo.getWorkflowId()).thenReturn(WORKFLOW_ID);

        try (MockedStatic<Workflow> workflowStatic = mockStatic(Workflow.class);
             MockedStatic<Async> asyncStatic = mockStatic(Async.class)) {
            workflowStatic.when(Workflow::getInfo).thenReturn(workflowInfo);
            workflowStatic.when(() -> Workflow.getLogger(any(Class.class))).thenReturn(NOPLogger.NOP_LOGGER);
            workflowStatic.when(() -> Workflow.newActivityStub(eq(WalletActivity.class), any(ActivityOptions.class)))
                .thenReturn(walletActivity);
            Promise<Void> mockPromise = mock();
            asyncStatic.when(() -> Async.procedure(any(Functions.Proc.class))).thenAnswer(inv -> {
                Functions.Proc proc = inv.getArgument(0);
                proc.apply();
                return mockPromise;
            });
            return workflow.checkout(request);
        }
    }

    private void stubCartAndPricing(long totalCents) {
        CartItem item = new CartItem("prod-1", 2, totalCents / 2);
        when(cartActivity.validateCart("user-1"))
            .thenReturn(new CartValidationResult("store-1", List.of(item), true, null));
        when(pricingActivity.calculatePrice(any()))
            .thenReturn(new PricingResult(totalCents, 0L, 0L, totalCents, "INR", "q-1", "qt-1"));
    }

    @Test
    void abortsBeforeInventoryAndPaymentWhenPriceDriftExceedsTolerance() {
        stubCartAndPricing(11200L);
        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 2000L, 10000L, 500L);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).contains("Price increased beyond tolerance");
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.getResultSnapshot()).isEqualTo(response);
        verifyNoInteractions(inventoryActivity, walletActivity, paymentActivity, orderActivity);
    }

    @Test
    void executesPartialWalletSplitTenderAndChargesPspOnlyRemainingBalance() {
        stubCartAndPricing(10000L);
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult("res-1", true));
        when(walletActivity.debitWallet(eq("user-1"), eq(3000L), eq(WORKFLOW_ID + ":wallet:debit"), anyString()))
            .thenReturn(new WalletDebitResult(WORKFLOW_ID + ":wallet:debit", true, 3000L, null));
        when(paymentActivity.authorizePayment(anyString(), eq(7000L), eq("INR"), eq("pm-1"), eq(WORKFLOW_ID + ":pay:auth")))
            .thenReturn(new PaymentAuthResult("pay-1", true, null));
        when(orderActivity.createOrder(any(OrderCreateRequest.class)))
            .thenReturn(new OrderCreationResult("ord-1", 12));

        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 3000L, 10000L, 0L);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderId()).isEqualTo("ord-1");
        assertThat(response.totalCents()).isEqualTo(10000L);
        assertThat(workflow.getResultSnapshot()).isEqualTo(response);
        verify(paymentActivity).authorizePayment(anyString(), eq(7000L), eq("INR"), eq("pm-1"), eq(WORKFLOW_ID + ":pay:auth"));
        verify(paymentActivity).capturePayment("pay-1", WORKFLOW_ID + ":pay:pay-1:capture");
        verify(orderActivity).confirmOrder("ord-1", WORKFLOW_ID + ":order:confirm");

        ArgumentCaptor<OrderCreateRequest> orderCaptor = ArgumentCaptor.forClass(OrderCreateRequest.class);
        verify(orderActivity).createOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().walletAmountCents()).isEqualTo(3000L);
    }

    @Test
    void skipsPspWhenWalletCoversFullOrderTotal() {
        stubCartAndPricing(5000L);
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult("res-2", true));
        when(walletActivity.debitWallet(eq("user-1"), eq(5000L), eq(WORKFLOW_ID + ":wallet:debit"), anyString()))
            .thenReturn(new WalletDebitResult(WORKFLOW_ID + ":wallet:debit", true, 5000L, null));
        when(orderActivity.createOrder(any(OrderCreateRequest.class)))
            .thenReturn(new OrderCreationResult("ord-2", 10));

        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 8000L, 5000L, 100L);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verifyNoInteractions(paymentActivity);
        verify(orderActivity).confirmOrder("ord-2", WORKFLOW_ID + ":order:confirm");

        ArgumentCaptor<OrderCreateRequest> orderCaptor = ArgumentCaptor.forClass(OrderCreateRequest.class);
        verify(orderActivity).createOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().walletAmountCents()).isEqualTo(5000L);
    }

    @Test
    void compensatesWalletAndInventoryWhenPspDeclinesAfterWalletDebit() {
        stubCartAndPricing(10000L);
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult("res-3", true));
        when(walletActivity.debitWallet(eq("user-1"), eq(3000L), eq(WORKFLOW_ID + ":wallet:debit"), anyString()))
            .thenReturn(new WalletDebitResult(WORKFLOW_ID + ":wallet:debit", true, 3000L, null));
        when(paymentActivity.authorizePayment(anyString(), eq(7000L), eq("INR"), eq("pm-1"), eq(WORKFLOW_ID + ":pay:auth")))
            .thenReturn(new PaymentAuthResult("pay-declined", false, "INSUFFICIENT_FUNDS"));

        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 3000L, null, null);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).contains("Payment declined");
        verify(walletActivity).refundWallet(eq("user-1"), eq(3000L), eq(WORKFLOW_ID + ":wallet:refund"), anyString());
        verify(inventoryActivity).releaseStock("res-3");
        verify(orderActivity, never()).createOrder(any());
    }

    @Test
    void compensatesExactPspRefundAndWalletRefundOnPostCaptureFailureAndRoutesWalletFailureToDlq() {
        stubCartAndPricing(10000L);
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult("res-4", true));
        when(walletActivity.debitWallet(eq("user-1"), eq(3000L), eq(WORKFLOW_ID + ":wallet:debit"), anyString()))
            .thenReturn(new WalletDebitResult(WORKFLOW_ID + ":wallet:debit", true, 3000L, null));
        when(paymentActivity.authorizePayment(anyString(), eq(7000L), eq("INR"), eq("pm-1"), eq(WORKFLOW_ID + ":pay:auth")))
            .thenReturn(new PaymentAuthResult("pay-4", true, null));
        when(orderActivity.createOrder(any(OrderCreateRequest.class)))
            .thenReturn(new OrderCreationResult("ord-4", 15));
        doThrow(new RuntimeException("confirm timeout"))
            .when(orderActivity).confirmOrder("ord-4", WORKFLOW_ID + ":order:confirm");
        doThrow(new RuntimeException("wallet down"))
            .when(walletActivity).refundWallet(eq("user-1"), eq(3000L), eq(WORKFLOW_ID + ":wallet:refund"), anyString());

        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 3000L, null, null);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).startsWith("FAILED: Order confirmation failed");
        assertThat(response.status()).doesNotContain("confirm timeout");
        verify(paymentActivity).refundPayment("pay-4", 7000L, WORKFLOW_ID + ":pay:pay-4:refund");
        verify(orderActivity).cancelOrder("ord-4");
        verify(dlqActivity).recordFailedCompensation(
            eq(WORKFLOW_ID), eq("refundWallet"), eq("user-1"), eq("wallet down"), eq("3000"));
        verify(inventoryActivity).releaseStock("res-4");
        verify(paymentActivity, never()).refundPayment(eq("pay-4"), eq(10000L), anyString());
    }

    @Test
    void releasesStockWithoutRefundOrPspWhenWalletDebitDeclines() {
        stubCartAndPricing(5000L);
        when(inventoryActivity.reserveStock(any()))
            .thenReturn(new InventoryReservationResult("res-5", true));
        when(walletActivity.debitWallet(eq("user-1"), eq(2000L), anyString(), anyString()))
            .thenReturn(new WalletDebitResult(WORKFLOW_ID + ":wallet:debit", false, 2000L, "Insufficient balance"));

        CheckoutRequest request = new CheckoutRequest(
            "user-1", "pm-1", null, "addr-1", null, null, 2000L, null, null);

        CheckoutResponse response = executeWorkflow(request);

        assertThat(response.status()).contains("Insufficient balance");
        verify(inventoryActivity).releaseStock("res-5");
        verify(walletActivity, never()).refundWallet(anyString(), anyLong(), anyString(), anyString());
        verifyNoInteractions(paymentActivity, orderActivity);
    }
}
