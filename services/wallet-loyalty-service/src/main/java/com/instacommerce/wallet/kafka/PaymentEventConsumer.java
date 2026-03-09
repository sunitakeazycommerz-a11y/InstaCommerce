package com.instacommerce.wallet.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.exception.OrderNotFoundException;
import com.instacommerce.wallet.service.WalletService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final WalletService walletService;
    private final OrderLookupClient orderLookupClient;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(WalletService walletService,
                                OrderLookupClient orderLookupClient,
                                ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.orderLookupClient = orderLookupClient;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.events", "payments.events"}, groupId = "wallet-loyalty-service")
    public void consume(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        String eventType = event.path("eventType").asText();

        if ("PaymentRefunded".equals(eventType)) {
            handlePaymentRefunded(event);
        }
    }

    private void handlePaymentRefunded(JsonNode event) {
        JsonNode payload = event.has("payload") ? event.path("payload") : event;

        String refundId = textOrThrow(payload, "refundId", "PaymentRefunded missing refundId");
        String paymentId = textOrThrow(payload, "paymentId", "PaymentRefunded missing paymentId");
        String orderId = textOrThrow(payload, "orderId", "PaymentRefunded missing orderId");
        long amountCents = payload.path("amountCents").asLong(0);
        if (amountCents <= 0) {
            throw new IllegalArgumentException("PaymentRefunded amountCents must be positive, got " + amountCents);
        }

        UUID orderUuid = UUID.fromString(orderId);
        OrderSnapshot order = orderLookupClient.findOrder(orderUuid)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        UUID userId = order.userId();

        try {
            walletService.credit(
                userId,
                amountCents,
                ReferenceType.REFUND,
                refundId,
                "Refund " + refundId + " for payment " + paymentId
            );
        } catch (DuplicateTransactionException ex) {
            log.info("Duplicate refund credit treated as idempotent success: refundId={}, orderId={}, userId={}",
                refundId, orderId, userId);
            return;
        }

        log.info("Processed PaymentRefunded: refundId={}, paymentId={}, orderId={}, userId={}, amountCents={}",
            refundId, paymentId, orderId, userId, amountCents);
    }

    private static String textOrThrow(JsonNode node, String field, String message) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
