package com.instacommerce.fulfillment.controller;

import com.instacommerce.fulfillment.dto.request.MarkDeliveredRequest;
import com.instacommerce.fulfillment.dto.request.ProofOfDeliveryRequest;
import com.instacommerce.fulfillment.dto.request.RiderHandoffRequest;
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

    @PostMapping("/fulfillment/orders/{orderId}/handoff")
    public DeliveryResponse verifyHandoff(@PathVariable UUID orderId,
                                          @Valid @RequestBody RiderHandoffRequest request) {
        return deliveryService.verifyRiderHandoff(orderId, request.riderId(), request.dispatchOtp());
    }

    @PostMapping("/fulfillment/orders/{orderId}/proof-of-delivery")
    public DeliveryResponse uploadProofOfDelivery(@PathVariable UUID orderId,
                                                  @Valid @RequestBody ProofOfDeliveryRequest request,
                                                  @AuthenticationPrincipal String principal,
                                                  Authentication authentication) {
        return deliveryService.recordProofOfDelivery(orderId, request.photoUrl(), request.format(),
            request.sizeBytes(), request.masked(), toUuidOrNull(principal), isPrivileged(authentication));
    }

    @PostMapping("/fulfillment/orders/{orderId}/delivered")
    public DeliveryResponse markDelivered(@PathVariable UUID orderId,
                                          @Valid @RequestBody(required = false) MarkDeliveredRequest request,
                                          @AuthenticationPrincipal String principal,
                                          Authentication authentication) {
        return deliveryService.markDelivered(orderId, toUuidOrNull(principal), isPrivileged(authentication));
    }

    private UUID toUuid(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid subject UUID");
        }
    }

    private UUID toUuidOrNull(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return hasAnyRole(authentication, "ROLE_ADMIN");
    }

    private boolean isPrivileged(Authentication authentication) {
        return hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_INTERNAL_SERVICE");
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> {
                String value = authority.getAuthority();
                for (String role : roles) {
                    if (role.equals(value)) {
                        return true;
                    }
                }
                return false;
            });
    }
}
