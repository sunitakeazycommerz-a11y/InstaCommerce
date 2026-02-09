package com.instacommerce.fulfillment.controller;

import com.instacommerce.fulfillment.dto.request.MarkDeliveredRequest;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.dto.response.TrackingResponse;
import com.instacommerce.fulfillment.service.DeliveryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class DeliveryController {
    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/orders/{orderId}/tracking")
    public TrackingResponse tracking(@PathVariable UUID orderId,
                                     @AuthenticationPrincipal String principal,
                                     Authentication authentication) {
        return deliveryService.getTracking(orderId, toUuid(principal), isAdmin(authentication));
    }

    @PostMapping("/fulfillment/orders/{orderId}/delivered")
    public DeliveryResponse markDelivered(@PathVariable UUID orderId,
                                          @Valid @RequestBody(required = false) MarkDeliveredRequest request) {
        return deliveryService.markDelivered(orderId);
    }

    private UUID toUuid(String principal) {
        return principal == null ? null : UUID.fromString(principal);
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
