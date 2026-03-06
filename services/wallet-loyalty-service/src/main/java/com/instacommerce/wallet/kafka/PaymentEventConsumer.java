package com.instacommerce.wallet.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(WalletService walletService, ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.events", "payments.events"}, groupId = "wallet-loyalty-service")
    public void consume(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText();

            if ("PaymentRefunded".equals(eventType)) {
                handlePaymentRefunded(event);
            }
        } catch (Exception ex) {
            log.error("Failed to process payment event: {}", message, ex);
        }
    }

    private void handlePaymentRefunded(JsonNode event) {
        String userId = event.path("userId").asText();
        String paymentId = event.path("paymentId").asText();
        long refundAmountCents = event.path("refundAmountCents").asLong();

        walletService.credit(
            java.util.UUID.fromString(userId),
            refundAmountCents,
            ReferenceType.REFUND,
            "refund-" + paymentId,
            "Refund for payment " + paymentId
        );

        log.info("Processed PaymentRefunded: paymentId={}, userId={}, amount={}",
            paymentId, userId, refundAmountCents);
    }
}
