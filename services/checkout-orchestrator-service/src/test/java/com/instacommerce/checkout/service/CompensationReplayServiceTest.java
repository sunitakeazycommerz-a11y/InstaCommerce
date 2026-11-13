package com.instacommerce.checkout.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompensationReplayServiceTest {

    @Mock
    private InventoryActivity inventoryActivity;

    @Mock
    private PaymentActivity paymentActivity;

    @Mock
    private OrderActivity orderActivity;

    @Mock
    private CouponActivity couponActivity;

    private CompensationReplayService replayService;

    @BeforeEach
    void setUp() {
        replayService = new CompensationReplayService(
            inventoryActivity, paymentActivity, orderActivity, couponActivity);
    }

    @Test
    void replaysReleaseStockAgainstInventoryActivity() {
        CompensationDlq entry = new CompensationDlq("wf-1", "releaseStock", "res-100", "timeout", null);

        replayService.replay(entry);

        verify(inventoryActivity).releaseStock("res-100");
        verifyNoInteractions(paymentActivity, orderActivity, couponActivity);
    }

    @Test
    void replaysVoidPaymentWithDeterministicWorkflowIdempotencyKey() {
        CompensationDlq entry = new CompensationDlq("wf-2", "voidPayment", "pay-200", "timeout", "15000");

        replayService.replay(entry);

        verify(paymentActivity).voidPayment("pay-200", "wf-2:pay:pay-200:void");
        verifyNoInteractions(inventoryActivity, orderActivity, couponActivity);
    }

    @Test
    void replaysRefundPaymentWithParsedPayloadAmountAndDeterministicKey() {
        CompensationDlq entry = new CompensationDlq("wf-3", "refundPayment", "pay-300", "gateway 503", " 24950 ");

        replayService.replay(entry);

        verify(paymentActivity).refundPayment("pay-300", 24950L, "wf-3:pay:pay-300:refund");
        verifyNoInteractions(inventoryActivity, orderActivity, couponActivity);
    }

    @Test
    void rejectsRefundPaymentWhenPayloadAmountIsMissingOrInvalid() {
        CompensationDlq missingPayload = new CompensationDlq("wf-3", "refundPayment", "pay-300", "gateway 503", null);
        CompensationDlq nonNumericPayload = new CompensationDlq("wf-3", "refundPayment", "pay-300", "gateway 503", "abc");
        CompensationDlq zeroAmountPayload = new CompensationDlq("wf-3", "refundPayment", "pay-300", "gateway 503", "0");

        assertThatThrownBy(() -> replayService.replay(missingPayload))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayService.replay(nonNumericPayload))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayService.replay(zeroAmountPayload))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentActivity);
    }

    @Test
    void replaysCancelOrderAgainstOrderActivity() {
        CompensationDlq entry = new CompensationDlq("wf-4", "cancelOrder", "ord-400", "order timeout", null);

        replayService.replay(entry);

        verify(orderActivity).cancelOrder("ord-400");
        verifyNoInteractions(inventoryActivity, paymentActivity, couponActivity);
    }

    @Test
    void replaysRollbackUsageWithCouponCodeAndUserId() {
        CompensationDlq entry = new CompensationDlq("wf-5", "rollbackUsage", "SAVE20:usr-500", "pricing down", null);

        replayService.replay(entry);

        verify(couponActivity).rollbackUsage("SAVE20", "usr-500");
        verifyNoInteractions(inventoryActivity, paymentActivity, orderActivity);
    }

    @Test
    void rejectsMalformedRollbackUsageTargetAndUnknownActivity() {
        CompensationDlq badRollback = new CompensationDlq("wf-5", "rollbackUsage", "SAVE20-only", "pricing down", null);
        CompensationDlq unknown = new CompensationDlq("wf-6", "clearCart", "usr-600", "unexpected", null);

        assertThatThrownBy(() -> replayService.replay(badRollback))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayService.replay(unknown))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(inventoryActivity, paymentActivity, orderActivity, couponActivity);
    }
}
