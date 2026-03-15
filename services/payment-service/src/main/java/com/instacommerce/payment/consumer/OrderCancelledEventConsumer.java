package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.PaymentService;
import com.instacommerce.payment.service.RefundService;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "payment.choreography",
    name = "order-cancelled-consumer-enabled",
    havingValue = "true")
public class OrderCancelledEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledEventConsumer.class);
    private static final String EVENT_TYPE = "OrderCancelled";
    private static final Set<PaymentStatus> TERMINAL_SKIP = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.REFUNDED,
        PaymentStatus.FAILED,
        PaymentStatus.DISPUTED);
    private static final Set<PaymentStatus> RETRYABLE_PENDING_STATES = Set.of(
        PaymentStatus.AUTHORIZE_PENDING,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final RefundService refundService;

    public OrderCancelledEventConsumer(ObjectMapper objectMapper,
                                       PaymentRepository paymentRepository,
                                       PaymentService paymentService,
                                       RefundService refundService) {
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @KafkaListener(
        topics = "${payment.choreography.order-cancelled-topic:" + TopicNames.ORDERS_EVENTS + "}",
        groupId = "${payment.choreography.order-cancelled-consumer-group:payment-service-order-cancelled}",
        properties = "auto.offset.reset=earliest")
    public void onOrderEvent(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

        if (!EVENT_TYPE.equals(envelope.eventType())) {
            log.debug("Ignoring event type={} at offset={}", envelope.eventType(), record.offset());
            return;
        }

        handleOrderCancelled(envelope, record);
    }

    // Visible for testing
    void handleOrderCancelled(EventEnvelope envelope, ConsumerRecord<String, String> record) throws Exception {
        OrderCancelledEvent event = parsePayload(envelope);

        UUID paymentId = resolvePaymentId(event, envelope.id());
        if (paymentId == null) {
            log.info("OrderCancelled event has no paymentId yet, no financial action needed. eventId={}, orderId={}",
                envelope.id(), event.orderId());
            return;
        }
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new IllegalStateException(
                "Payment not found for paymentId=%s, orderId=%s, eventId=%s"
                    .formatted(paymentId, event.orderId(), envelope.id())));
        String orderId = event.orderId() != null ? event.orderId() : envelope.aggregateId();
        String idempotencyKey = orderId + "-cancellation-refund";

        routeByPaymentState(payment, event, idempotencyKey, envelope.id());
    }

    // Package-private for testing
    void routeByPaymentState(Payment payment, OrderCancelledEvent event,
                             String idempotencyKey, String eventId) {
        PaymentStatus status = payment.getStatus();

        if (TERMINAL_SKIP.contains(status)) {
            log.info("Payment {} already in terminal state {}, skipping OrderCancelled eventId={}",
                payment.getId(), status, eventId);
            return;
        }

        if (RETRYABLE_PENDING_STATES.contains(status)) {
            throw new IllegalStateException("Payment %s still in pending state %s for OrderCancelled eventId=%s"
                .formatted(payment.getId(), status, eventId));
        }

        switch (status) {
            case AUTHORIZED -> {
                log.info("Voiding authorization for payment={}, orderId={}, eventId={}",
                    payment.getId(), event.orderId(), eventId);
                paymentService.voidAuth(payment.getId(), idempotencyKey);
            }
            case CAPTURED, PARTIALLY_REFUNDED -> {
                long refundableAmount = payment.getCapturedCents() - payment.getRefundedCents();
                if (refundableAmount <= 0) {
                    log.info("Payment {} fully refunded (refundable=0), skipping eventId={}",
                        payment.getId(), eventId);
                    return;
                }
                String reason = event.reason() != null ? event.reason() : "Order cancellation";
                log.info("Refunding {} cents for payment={}, orderId={}, eventId={}",
                    refundableAmount, payment.getId(), event.orderId(), eventId);
                refundService.refund(payment.getId(),
                    new RefundRequest(refundableAmount, reason, idempotencyKey));
            }
            default -> log.warn("Unexpected payment status {} for payment={}, eventId={}",
                status, payment.getId(), eventId);
        }
    }

    private OrderCancelledEvent parsePayload(EventEnvelope envelope) throws Exception {
        if (envelope.payload() != null && !envelope.payload().isNull()) {
            return objectMapper.treeToValue(envelope.payload(), OrderCancelledEvent.class);
        }
        throw new IllegalArgumentException("OrderCancelled event has no payload");
    }

    private UUID resolvePaymentId(OrderCancelledEvent event, String eventId) {
        if (event.paymentId() == null || event.paymentId().isBlank()) {
            return null;
        }
        return UUID.fromString(event.paymentId());
    }
}
