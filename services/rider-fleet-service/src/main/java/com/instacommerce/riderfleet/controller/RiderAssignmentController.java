package com.instacommerce.riderfleet.controller;

import com.instacommerce.riderfleet.dto.request.AssignRiderRequest;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/riders")
public class RiderAssignmentController {
    private final RiderAssignmentService assignmentService;

    public RiderAssignmentController(RiderAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping("/assign")
    public ResponseEntity<Map<String, UUID>> assignRider(@Valid @RequestBody AssignRiderRequest request) {
        UUID riderId = assignmentService.assignRider(
            request.orderId(), request.storeId(), request.pickupLat(), request.pickupLng());
        return ResponseEntity.ok(Map.of("riderId", riderId, "orderId", request.orderId()));
    }
}
