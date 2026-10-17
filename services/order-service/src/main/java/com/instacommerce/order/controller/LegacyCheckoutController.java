package com.instacommerce.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tombstone controller returning HTTP 410 Gone for legacy direct checkout calls.
 * Checkout orchestration is exclusively handled by checkout-orchestrator-service.
 */
@RestController
@RequestMapping({"/checkout", "/api/checkout"})
public class LegacyCheckoutController {

    @PostMapping
    public ResponseEntity<Map<String, String>> checkout() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "code", "CHECKOUT_MOVED",
            "message", "Direct checkout on order-service has been decommissioned. Use checkout-orchestrator-service."
        ));
    }
}
