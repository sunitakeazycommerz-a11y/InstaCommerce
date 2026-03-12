package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.consumer.WebhookKafkaBridge.Result;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for enriched webhook events produced by the Go
 * {@code payment-webhook-service}. Deserializes each record into a
 * {@link WebhookTransportEvent} and delegates to {@link WebhookKafkaBridge}
 * which validates the envelope and forwards the raw PSP payload to the
 * existing Stripe webhook handler.
 * <p>
 * Safe-skip results from the bridge (unsupported PSP, old schema version,
 * missing payload, validation failure) are logged and the offset is committed
 * normally — they must not route to the dead-letter topic because they are
 * structurally unprocessable and retrying would be pointless. True processing
 * errors (JSON parse failures, handler exceptions) propagate to the configured
 * {@link org.springframework.kafka.listener.CommonErrorHandler} for
 * retry / DLT routing.
 * <p>
 * The consumer is <strong>off by default</strong> and activated via
 * {@code payment.webhook.kafka-consumer-enabled=true}.
 */
@Component
@ConditionalOnProperty(
    prefix = "payment.webhook",
    name = "kafka-consumer-enabled",
    havingValue = "true")
public class PaymentWebhookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final WebhookKafkaBridge bridge;

    public PaymentWebhookEventConsumer(ObjectMapper objectMapper, WebhookKafkaBridge bridge) {
        this.objectMapper = objectMapper;
        this.bridge = bridge;
    }

    @KafkaListener(
        topics = "${payment.webhook.kafka-consumer-topic:payment.webhooks}",
        groupId = "${payment.webhook.kafka-consumer-group:payment-service-webhook}",
        properties = "auto.offset.reset=earliest")
    public void onWebhookEvent(ConsumerRecord<String, String> record) throws Exception {
        log.debug("Received webhook transport record at topic={}, partition={}, offset={}",
            record.topic(), record.partition(), record.offset());

        WebhookTransportEvent event = objectMapper.readValue(record.value(), WebhookTransportEvent.class);

        Result result = bridge.forward(event);

        if (result == Result.PROCESSED) {
            log.info("Processed webhook transport event id={}, type={}, psp={}",
                event.id(), event.eventType(), event.psp());
        } else {
            log.info("Skipped webhook transport event id={}, result={}, offset={}",
                event.id(), result, record.offset());
        }
    }
}
