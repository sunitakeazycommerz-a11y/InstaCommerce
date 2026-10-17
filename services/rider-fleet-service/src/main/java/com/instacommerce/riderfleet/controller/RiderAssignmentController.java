package com.instacommerce.riderfleet.controller;

import com.instacommerce.riderfleet.dto.request.AssignRiderRequest;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/riders", "/api/riders"})
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

    @PostMapping("/assignments/{orderId}/complete")
    public ResponseEntity<Void> completeDelivery(@PathVariable UUID orderId) {
        assignmentService.completeDelivery(orderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/assignments/{orderId}/cancel")
    public ResponseEntity<Void> cancelAssignment(@PathVariable UUID orderId,
                                                 @RequestParam(required = false, defaultValue = "user_or_system_cancel") String reason) {
        assignmentService.cancelAssignment(orderId, reason);
        return ResponseEntity.ok().build();
    }
}
