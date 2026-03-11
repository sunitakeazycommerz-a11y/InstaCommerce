package com.instacommerce.payment.controller;

import com.instacommerce.payment.webhook.WebhookEventHandler;
import com.instacommerce.payment.webhook.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/webhook")
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventHandler webhookEventHandler;

    public WebhookController(WebhookSignatureVerifier signatureVerifier,
                             WebhookEventHandler webhookEventHandler) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventHandler = webhookEventHandler;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody String payload,
                                                @RequestHeader(value = "Stripe-Signature", required = true)
                                                String signature) {
        if (!signatureVerifier.verify(payload, signature)) {
            return ResponseEntity.badRequest().body("Bad Request");
        }
        try {
            webhookEventHandler.handle(payload);
        } catch (Exception ex) {
            log.error("Webhook processing failed", ex);
            return ResponseEntity.internalServerError().body("Internal Server Error");
        }
        return ResponseEntity.ok("OK");
    }
}
