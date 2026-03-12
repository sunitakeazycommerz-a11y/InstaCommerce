package com.instacommerce.payment.consumer;

import com.instacommerce.payment.webhook.WebhookEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bridge between Kafka-consumed {@link WebhookTransportEvent} messages and the
 * existing {@link WebhookEventHandler} that processes raw Stripe payloads.
 * <p>
 * The Go {@code payment-webhook-service} now publishes enriched v2 messages
 * containing a verbatim {@code raw_psp_payload}. This bridge validates the
 * transport envelope and forwards the raw payload into the same code path used
 * by the direct-HTTP webhook endpoint, avoiding any lossy reconstruction from
 * canonical fields.
 * <p>
 * Only Stripe is supported initially; other PSPs are safely skipped with
 * informational logging so the consumer never fails on an unsupported provider.
 */
@Component
public class WebhookKafkaBridge {

    private static final Logger log = LoggerFactory.getLogger(WebhookKafkaBridge.class);

    /** Minimum schema version that carries the raw_psp_payload field. */
    static final int MIN_SCHEMA_VERSION = 2;

    private static final String PSP_STRIPE = "stripe";

    private final WebhookEventHandler webhookEventHandler;

    public WebhookKafkaBridge(WebhookEventHandler webhookEventHandler) {
        this.webhookEventHandler = webhookEventHandler;
    }

    /**
     * Outcome of a single bridge invocation. The upcoming Kafka consumer can
     * use this to decide offset-commit strategy, metrics, or DLT routing.
     */
    public enum Result {
        /** Raw payload was forwarded to {@link WebhookEventHandler}. */
        PROCESSED,
        /** PSP is not yet supported; event was safely skipped. */
        SKIPPED_UNSUPPORTED_PSP,
        /** Schema version too old to carry a raw payload. */
        SKIPPED_SCHEMA_VERSION_TOO_OLD,
        /** The raw_psp_payload field was absent or null. */
        SKIPPED_MISSING_RAW_PAYLOAD,
        /** Required envelope fields (id, psp, event_type) were missing. */
        SKIPPED_VALIDATION_FAILED
    }

    /**
     * Validate the transport event and, if eligible, forward the raw PSP
     * payload to the existing Stripe webhook handler.
     *
     * @param event deserialized Kafka transport message (never null)
     * @return outcome describing what happened
     */
    public Result forward(WebhookTransportEvent event) {
        if (event.id() == null || event.id().isBlank()) {
            log.warn("Webhook transport event missing id, skipping");
            return Result.SKIPPED_VALIDATION_FAILED;
        }
        if (event.psp() == null || event.psp().isBlank()) {
            log.warn("Webhook transport event {} missing psp, skipping", event.id());
            return Result.SKIPPED_VALIDATION_FAILED;
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            log.warn("Webhook transport event {} missing event_type, skipping", event.id());
            return Result.SKIPPED_VALIDATION_FAILED;
        }

        if (event.schemaVersion() < MIN_SCHEMA_VERSION) {
            log.warn("Webhook transport event {} has schema_version={}, need >= {}; "
                    + "raw payload unavailable, skipping",
                event.id(), event.schemaVersion(), MIN_SCHEMA_VERSION);
            return Result.SKIPPED_SCHEMA_VERSION_TOO_OLD;
        }

        if (event.rawPspPayload() == null || event.rawPspPayload().isNull()
                || event.rawPspPayload().isMissingNode()) {
            log.warn("Webhook transport event {} (v{}) missing raw_psp_payload, skipping",
                event.id(), event.schemaVersion());
            return Result.SKIPPED_MISSING_RAW_PAYLOAD;
        }

        if (!PSP_STRIPE.equals(event.psp())) {
            log.info("Webhook transport event {} from unsupported PSP '{}', skipping gracefully",
                event.id(), event.psp());
            return Result.SKIPPED_UNSUPPORTED_PSP;
        }

        String rawPayload = event.rawPspPayload().toString();
        log.debug("Forwarding webhook transport event {} (type={}) to WebhookEventHandler",
            event.id(), event.eventType());
        webhookEventHandler.handle(rawPayload);
        return Result.PROCESSED;
    }
}
