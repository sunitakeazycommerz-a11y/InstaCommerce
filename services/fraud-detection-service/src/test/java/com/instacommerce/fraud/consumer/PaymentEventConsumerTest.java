package com.instacommerce.fraud.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fraud.service.BlocklistService;
import com.instacommerce.fraud.service.VelocityService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private VelocityService velocityService;

    @Mock
    private BlocklistService blocklistService;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(objectMapper, velocityService, blocklistService, 2);
    }

    @Test
    void paymentDisputedIncrementsChargebackCountersAndAutoBlocklistsAtThresholdIdempotently() {
        String userId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "evt-dispute-1",
                  "eventType": "PaymentDisputed",
                  "payload": {
                    "userId": "%s",
                    "orderId": "%s",
                    "paymentId": "%s",
                    "disputeAmountCents": 12500,
                    "currency": "INR",
                    "reason": "fraudulent_charge",
                    "deviceFingerprint": "fp-device-9",
                    "ipAddress": "203.0.113.42"
                  }
                }
                """.formatted(userId, UUID.randomUUID(), UUID.randomUUID());

        when(velocityService.getCount("USER", userId, "CHARGEBACKS_30D")).thenReturn(2L);
        when(blocklistService.isBlocked("USER", userId)).thenReturn(false, true);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("payments.events", 0, 1L, userId, payload);
        consumer.onPaymentEvent(record);

        verify(velocityService).incrementCounter("USER", userId, "CHARGEBACKS_30D");
        verify(velocityService).incrementCounter("DEVICE", "fp-device-9", "CHARGEBACKS_30D");
        verify(velocityService).incrementCounter("IP", "203.0.113.42", "CHARGEBACKS_30D");
        verify(blocklistService).block(
            eq("USER"),
            eq(userId),
            eq("Auto-blocked: chargeback threshold breached (fraudulent_charge)"),
            eq(null),
            eq("system:payment-disputed"));

        consumer.onPaymentEvent(record);
        verify(blocklistService).block(any(), any(), any(), any(), any());
    }

    @Test
    void paymentDisputedBelowThresholdIncrementsCounterWithoutBlocking() {
        String userId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "evt-dispute-2",
                  "eventType": "PaymentDisputed",
                  "payload": {
                    "userId": "%s",
                    "orderId": "%s",
                    "paymentId": "%s",
                    "disputeAmountCents": 4500,
                    "currency": "INR"
                  }
                }
                """.formatted(userId, UUID.randomUUID(), UUID.randomUUID());

        when(velocityService.getCount("USER", userId, "CHARGEBACKS_30D")).thenReturn(1L);

        consumer.onPaymentEvent(new ConsumerRecord<>("payments.events", 0, 2L, userId, payload));

        verify(velocityService).incrementCounter("USER", userId, "CHARGEBACKS_30D");
        verifyNoInteractions(blocklistService);
    }

    @Test
    void paymentRefundFailedIncrementsRefundFailures24hCounters() {
        String userId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "evt-refund-fail-1",
                  "eventType": "PaymentRefundFailed",
                  "payload": {
                    "userId": "%s",
                    "orderId": "%s",
                    "paymentId": "%s",
                    "refundId": "%s",
                    "amountCents": 3200,
                    "currency": "INR",
                    "reason": "destination_account_closed",
                    "failureSource": "gateway",
                    "deviceFingerprint": "fp-device-7",
                    "ipAddress": "198.51.100.10"
                  }
                }
                """.formatted(userId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        consumer.onPaymentEvent(new ConsumerRecord<>("payments.events", 0, 3L, userId, payload));

        verify(velocityService).incrementCounter("USER", userId, "REFUND_FAILURES_24H");
        verify(velocityService).incrementCounter("DEVICE", "fp-device-7", "REFUND_FAILURES_24H");
        verify(velocityService).incrementCounter("IP", "198.51.100.10", "REFUND_FAILURES_24H");
        verify(blocklistService, never()).block(any(), any(), any(), any(), any());
    }
}
