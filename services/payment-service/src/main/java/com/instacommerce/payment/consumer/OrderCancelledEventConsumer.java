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
    private static final String PARTIAL_REFUND_EVENT_TYPE = "OrderPartiallyRefunded";
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

        if (EVENT_TYPE.equals(envelope.eventType())) {
            handleOrderCancelled(envelope, record);
        } else if (PARTIAL_REFUND_EVENT_TYPE.equals(envelope.eventType())) {
            handleOrderPartiallyRefunded(envelope);
        } else {
            log.debug("Ignoring event type={} at offset={}", envelope.eventType(), record.offset());
        }
    }

    void handleOrderCancelled(EventEnvelope envelope, ConsumerRecord<String, String> record) throws Exception {
        OrderCancelledEvent event = parsePayload(envelope);
        Payment payment = resolvePaymentOrNull(event, envelope.aggregateId(), envelope.id(), EVENT_TYPE);
        if (payment == null) {
            return;
        }
        String orderId = event.orderId() != null ? event.orderId() : envelope.aggregateId();
        String idempotencyKey = orderId + "-cancellation-refund";

        routeByPaymentState(payment, event, idempotencyKey, envelope.id());
    }

    void handleOrderPartiallyRefunded(EventEnvelope envelope) throws Exception {
        OrderCancelledEvent event = parsePayload(envelope);
        Payment payment = resolvePaymentOrNull(event, envelope.aggregateId(), envelope.id(), PARTIAL_REFUND_EVENT_TYPE);
        if (payment == null) {
            return;
        }
        PaymentStatus status = payment.getStatus();
        if (TERMINAL_SKIP.contains(status)) {
            log.info("Payment {} already in terminal state {}, skipping OrderPartiallyRefunded eventId={}",
                payment.getId(), status, envelope.id());
            return;
        }
        if (RETRYABLE_PENDING_STATES.contains(status)) {
            throw new IllegalStateException(
                "Payment %s still in pending state %s for OrderPartiallyRefunded eventId=%s"
                    .formatted(payment.getId(), status, envelope.id()));
        }
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Ignoring OrderPartiallyRefunded in state {} for payment={}, eventId={}",
                status, payment.getId(), envelope.id());
            return;
        }
        long targetPspRefund = event.pspRefundCents() != null
            ? Math.max(0L, event.pspRefundCents())
            : Math.max(0L,
                (event.refundedCents() != null ? event.refundedCents() : 0L)
                    - (event.walletRefundCents() != null ? event.walletRefundCents() : 0L));
        long remainingCaptured = Math.max(0L, payment.getCapturedCents() - payment.getRefundedCents());
        long refundable = Math.min(remainingCaptured, targetPspRefund);
        if (refundable <= 0L) {
            log.info("OrderPartiallyRefunded requires 0 PSP refund for payment={}, eventId={}",
                payment.getId(), envelope.id());
            return;
        }
        String orderId = event.orderId() != null ? event.orderId() : envelope.aggregateId();
        String idempotencyKey = orderId + "-partial-refund-" + envelope.id();
        String reason = event.reason() != null && !event.reason().isBlank()
            ? event.reason()
            : "Order partial refund";
        log.info("Refunding {} PSP cents for partial refund payment={}, orderId={}, eventId={}",
            refundable, payment.getId(), orderId, envelope.id());
        refundService.refund(payment.getId(), new RefundRequest(refundable, reason, idempotencyKey));
    }

    private Payment resolvePaymentOrNull(OrderCancelledEvent event,
                                         String aggregateId,
                                         String eventId,
                                         String eventType) {
        UUID paymentId = resolvePaymentId(event, eventId);
        if (paymentId != null) {
            return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                    "Payment not found for paymentId=%s, orderId=%s, eventId=%s"
                        .formatted(paymentId, event.orderId(), eventId)));
        }
        String orderIdStr = event.orderId() != null && !event.orderId().isBlank()
            ? event.orderId() : aggregateId;
        Payment payment = null;
        if (orderIdStr != null && !orderIdStr.isBlank()) {
            try {
                UUID orderUuid = UUID.fromString(orderIdStr);
                payment = paymentRepository.findByOrderId(orderUuid).orElse(null);
            } catch (IllegalArgumentException ex) {
                log.debug("Invalid orderId UUID '{}' on {} eventId={}", orderIdStr, eventType, eventId);
            }
        }
        if (payment == null) {
            log.info("{} event has no active payment found. eventId={}, orderId={}",
                eventType, eventId, orderIdStr);
        }
        return payment;
    }

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
                long remainingCaptured = Math.max(0L, payment.getCapturedCents() - payment.getRefundedCents());
                long walletAmount = event.walletAmountCents() != null
                    ? Math.max(0L, event.walletAmountCents())
                    : 0L;
                long refundableAmount = remainingCaptured;
                if (walletAmount > 0 && event.totalCents() != null) {
                    long pspChargePortion = Math.max(0L, event.totalCents() - walletAmount);
                    refundableAmount = Math.min(remainingCaptured, pspChargePortion);
                }
                if (refundableAmount <= 0) {
                    log.info("Payment {} has no PSP refundable balance remaining (refundable=0), skipping eventId={}",
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
