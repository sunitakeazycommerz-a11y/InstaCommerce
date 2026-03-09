package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PaymentAuthResult;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentActivityImplTest {

    private RestTemplate restTemplate;
    private PaymentActivityImpl activity;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        activity = new PaymentActivityImpl(restTemplate);
    }

    @Nested
    @DisplayName("resolveIdempotencyKey behavior via authorizePayment")
    class IdempotencyKeyResolution {

        @Test
        @DisplayName("provided key is used as-is, not appended with activityId")
        void providedKeyUsedAsIs() {
            String stableKey = "checkout-wf1:pay:auth";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn("activity-id-12345");

                when(restTemplate.postForObject(eq("/payments/authorize"), any(), eq(PaymentAuthResult.class)))
                    .thenReturn(new PaymentAuthResult("pay_1", true, null));

                activity.authorizePayment(5000L, "pm_abc", stableKey);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/authorize"), bodyCaptor.capture(), eq(PaymentAuthResult.class));

                Map<String, Object> body = bodyCaptor.getValue();
                assertEquals(stableKey, body.get("idempotencyKey"),
                    "Provided key must be passed through unchanged — no activityId suffix");
                assertFalse(body.get("idempotencyKey").toString().contains("activity-id"),
                    "Key must NOT contain activityId");
            }
        }

        @Test
        @DisplayName("null key falls back to activityId")
        void nullKeyFallsBackToActivityId() {
            String activityId = "activity-fallback-789";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn(activityId);

                when(restTemplate.postForObject(eq("/payments/authorize"), any(), eq(PaymentAuthResult.class)))
                    .thenReturn(new PaymentAuthResult("pay_2", true, null));

                activity.authorizePayment(3000L, "pm_xyz", null);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/authorize"), bodyCaptor.capture(), eq(PaymentAuthResult.class));

                assertEquals(activityId, bodyCaptor.getValue().get("idempotencyKey"),
                    "Null key should fall back to activityId");
            }
        }

        @Test
        @DisplayName("blank key falls back to activityId")
        void blankKeyFallsBackToActivityId() {
            String activityId = "activity-blank-fallback";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn(activityId);

                when(restTemplate.postForObject(eq("/payments/authorize"), any(), eq(PaymentAuthResult.class)))
                    .thenReturn(new PaymentAuthResult("pay_3", true, null));

                activity.authorizePayment(1000L, "pm_blank", "   ");

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/authorize"), bodyCaptor.capture(), eq(PaymentAuthResult.class));

                assertEquals(activityId, bodyCaptor.getValue().get("idempotencyKey"),
                    "Blank key should fall back to activityId");
            }
        }
    }

    @Nested
    @DisplayName("capturePayment key forwarding")
    class CapturePayment {

        @Test
        @DisplayName("capture uses provided key as-is")
        void captureUsesProvidedKey() {
            String captureKey = "checkout-wf1:pay:pay_1:capture";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn("act-99");

                when(restTemplate.postForObject(eq("/payments/{paymentId}/capture"), any(), eq(Void.class), eq("pay_1")))
                    .thenReturn(null);

                activity.capturePayment("pay_1", captureKey);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/{paymentId}/capture"), bodyCaptor.capture(), eq(Void.class), eq("pay_1"));

                assertEquals(captureKey, bodyCaptor.getValue().get("idempotencyKey"));
            }
        }
    }

    @Nested
    @DisplayName("refundPayment key forwarding")
    class RefundPayment {

        @Test
        @DisplayName("refund uses provided key as-is")
        void refundUsesProvidedKey() {
            String refundKey = "checkout-wf1:pay:pay_1:refund";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn("act-refund");

                when(restTemplate.postForObject(eq("/payments/{paymentId}/refund"), any(), eq(Void.class), eq("pay_1")))
                    .thenReturn(null);

                activity.refundPayment("pay_1", 5000L, refundKey);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/{paymentId}/refund"), bodyCaptor.capture(), eq(Void.class), eq("pay_1"));

                assertEquals(refundKey, bodyCaptor.getValue().get("idempotencyKey"));
            }
        }
    }

    @Nested
    @DisplayName("voidPayment key forwarding")
    class VoidPayment {

        @Test
        @DisplayName("void uses provided key as-is")
        void voidUsesProvidedKey() {
            String voidKey = "checkout-wf1:pay:pay_1:void";

            try (var mockedActivity = mockStatic(Activity.class)) {
                ActivityExecutionContext ctx = mock(ActivityExecutionContext.class);
                ActivityInfo info = mock(ActivityInfo.class);
                mockedActivity.when(Activity::getExecutionContext).thenReturn(ctx);
                when(ctx.getInfo()).thenReturn(info);
                when(info.getActivityId()).thenReturn("act-void");

                when(restTemplate.postForObject(eq("/payments/{paymentId}/void"), any(), eq(Void.class), eq("pay_1")))
                    .thenReturn(null);

                activity.voidPayment("pay_1", voidKey);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForObject(eq("/payments/{paymentId}/void"), bodyCaptor.capture(), eq(Void.class), eq("pay_1"));

                assertEquals(voidKey, bodyCaptor.getValue().get("idempotencyKey"));
            }
        }
    }
}
