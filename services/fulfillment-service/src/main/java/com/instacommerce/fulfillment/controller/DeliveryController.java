package com.instacommerce.fulfillment.controller;

import com.instacommerce.fulfillment.dto.request.MarkDeliveredRequest;
import com.instacommerce.fulfillment.dto.request.MarkDeliveryFailedRequest;
import com.instacommerce.fulfillment.dto.request.ProofOfDeliveryRequest;
import com.instacommerce.fulfillment.dto.request.RiderHandoffRequest;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.dto.response.TrackingResponse;
import com.instacommerce.fulfillment.service.DeliveryService;
import com.instacommerce.platform.security.SecurityRoleUtils;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(DeliveryController.class);
    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/orders/{orderId}/tracking")
    public TrackingResponse tracking(@PathVariable UUID orderId,
                                     @AuthenticationPrincipal String principal,
                                     Authentication authentication) {
        return deliveryService.getTracking(
            orderId, toUuid(principal, authentication), SecurityRoleUtils.isAdmin(authentication));
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
            request.sizeBytes(), request.masked(),
            toUuid(principal, authentication), SecurityRoleUtils.isPrivileged(authentication));
    }

    @PostMapping("/fulfillment/orders/{orderId}/delivered")
    public DeliveryResponse markDelivered(@PathVariable UUID orderId,
                                          @Valid @RequestBody(required = false) MarkDeliveredRequest request,
                                          @AuthenticationPrincipal String principal,
                                          Authentication authentication) {
        return deliveryService.markDelivered(
            orderId, toUuid(principal, authentication), SecurityRoleUtils.isPrivileged(authentication));
    }

    @PostMapping("/fulfillment/orders/{orderId}/failed")
    public DeliveryResponse markDeliveryFailed(@PathVariable UUID orderId,
                                               @Valid @RequestBody MarkDeliveryFailedRequest request,
                                               @AuthenticationPrincipal String principal,
                                               Authentication authentication) {
        return deliveryService.markDeliveryFailed(
            orderId, request, toUuid(principal, authentication), SecurityRoleUtils.isPrivileged(authentication));
    }

    private UUID toUuid(String principal, Authentication authentication) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal.trim());
        } catch (IllegalArgumentException ex) {
            if (SecurityRoleUtils.isInternalService(authentication)) {
                logger.debug("Using non-UUID principal '{}' for internal service caller", principal);
                return null;
            }
            logger.warn("Rejecting malformed principal '{}' as invalid subject UUID", principal);
            throw new IllegalArgumentException("Invalid subject UUID");
        }
    }
}
