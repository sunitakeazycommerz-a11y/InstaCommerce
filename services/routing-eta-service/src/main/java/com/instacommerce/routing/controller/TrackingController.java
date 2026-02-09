package com.instacommerce.routing.controller;

import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.dto.response.TrackingResponse;
import com.instacommerce.routing.service.TrackingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tracking")
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping("/location")
    public ResponseEntity<TrackingResponse> recordLocation(
            @Valid @RequestBody LocationUpdateRequest request) {
        return ResponseEntity.ok(trackingService.recordLocation(request));
    }
}
