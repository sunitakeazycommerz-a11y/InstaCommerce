package com.instacommerce.pricing.controller;

import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.service.PricingService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class PriceLookupController {

    private final PricingService pricingService;

    public PriceLookupController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/api/v1/prices/{id}")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<PricedItem> getProductPriceByV1Alias(
            @PathVariable UUID id,
            @RequestParam(value = "storeId", required = false) UUID storeId) {
        long priceCents = storeId != null
                ? pricingService.calculatePrice(id, storeId)
                : pricingService.calculatePrice(id);
        PricedItem item = new PricedItem(id, priceCents, 1, priceCents, "INR");
        return ResponseEntity.ok(item);
    }
}
