package com.instacommerce.routing.controller;

import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAResponse;
import com.instacommerce.routing.dto.response.TrackingResponse;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.ETAService;
import com.instacommerce.routing.service.TrackingService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final ETAService etaService;
    private final TrackingService trackingService;

    public DeliveryController(DeliveryService deliveryService,
                              ETAService etaService,
                              TrackingService trackingService) {
        this.deliveryService = deliveryService;
        this.etaService = etaService;
        this.trackingService = trackingService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<DeliveryResponse> getByOrderId(@PathVariable UUID orderId) {
        return ResponseEntity.ok(deliveryService.getByOrderId(orderId));
    }

    @GetMapping("/{id}/eta")
    public ResponseEntity<ETAResponse> getETA(@PathVariable UUID id) {
        DeliveryResponse delivery = deliveryService.getById(id);
        TrackingResponse latest = trackingService.findLatestLocation(id).orElse(null);
        double fromLat = latest != null
            ? latest.latitude().doubleValue()
            : delivery.pickupLat().doubleValue();
        double fromLng = latest != null
            ? latest.longitude().doubleValue()
            : delivery.pickupLng().doubleValue();
        ETAResponse eta = etaService.calculateETA(
            fromLat, fromLng,
            delivery.dropoffLat().doubleValue(), delivery.dropoffLng().doubleValue());
        return ResponseEntity.ok(eta);
    }

    @GetMapping("/{id}/tracking")
    public ResponseEntity<List<TrackingResponse>> getTracking(@PathVariable UUID id) {
        return ResponseEntity.ok(trackingService.getTrackingHistory(id));
    }
}
