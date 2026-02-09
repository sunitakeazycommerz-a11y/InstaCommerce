package com.instacommerce.riderfleet.controller;

import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.dto.request.LocationUpdateRequest;
import com.instacommerce.riderfleet.dto.response.EarningsSummaryResponse;
import com.instacommerce.riderfleet.dto.response.RiderResponse;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.service.RiderAvailabilityService;
import com.instacommerce.riderfleet.service.RiderEarningsService;
import com.instacommerce.riderfleet.service.RiderService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/riders")
public class RiderController {
    private final RiderService riderService;
    private final RiderAvailabilityService availabilityService;
    private final RiderEarningsService earningsService;
    private final RiderAvailabilityRepository availabilityRepository;

    public RiderController(RiderService riderService,
                           RiderAvailabilityService availabilityService,
                           RiderEarningsService earningsService,
                           RiderAvailabilityRepository availabilityRepository) {
        this.riderService = riderService;
        this.availabilityService = availabilityService;
        this.earningsService = earningsService;
        this.availabilityRepository = availabilityRepository;
    }

    @PostMapping("/{id}/availability")
    public ResponseEntity<Void> toggleAvailability(@PathVariable UUID id,
                                                    @RequestParam boolean available) {
        availabilityService.toggleAvailability(id, available);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/location")
    public ResponseEntity<Void> updateLocation(@PathVariable UUID id,
                                                @Valid @RequestBody LocationUpdateRequest request) {
        availabilityService.updateLocation(id, request.lat(), request.lng());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/available")
    public ResponseEntity<List<RiderAvailability>> getAvailableRiders(@RequestParam UUID storeId) {
        List<RiderAvailability> available = availabilityRepository.findByIsAvailableTrueAndStoreId(storeId);
        return ResponseEntity.ok(available);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RiderResponse> getRider(@PathVariable UUID id) {
        return ResponseEntity.ok(riderService.getRider(id));
    }

    @GetMapping("/{id}/earnings")
    public ResponseEntity<EarningsSummaryResponse> getEarnings(@PathVariable UUID id,
                                                                @RequestParam Instant from,
                                                                @RequestParam Instant to) {
        return ResponseEntity.ok(earningsService.getEarningsSummary(id, from, to));
    }
}
