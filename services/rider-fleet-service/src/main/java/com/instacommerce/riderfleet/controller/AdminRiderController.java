package com.instacommerce.riderfleet.controller;

import com.instacommerce.riderfleet.dto.request.CreateRiderRequest;
import com.instacommerce.riderfleet.dto.response.RiderResponse;
import com.instacommerce.riderfleet.service.RiderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/riders")
public class AdminRiderController {
    private final RiderService riderService;

    public AdminRiderController(RiderService riderService) {
        this.riderService = riderService;
    }

    @PostMapping
    public ResponseEntity<RiderResponse> createRider(@Valid @RequestBody CreateRiderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(riderService.createRider(request));
    }

    @GetMapping
    public ResponseEntity<List<RiderResponse>> listRiders() {
        return ResponseEntity.ok(riderService.getAllRiders());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RiderResponse> getRider(@PathVariable UUID id) {
        return ResponseEntity.ok(riderService.getRider(id));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<RiderResponse> activateRider(@PathVariable UUID id) {
        return ResponseEntity.ok(riderService.activateRider(id));
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<RiderResponse> suspendRider(@PathVariable UUID id) {
        return ResponseEntity.ok(riderService.suspendRider(id));
    }

    @PostMapping("/{id}/onboard")
    public ResponseEntity<RiderResponse> onboardRider(@PathVariable UUID id) {
        return ResponseEntity.ok(riderService.onboardRider(id));
    }
}
