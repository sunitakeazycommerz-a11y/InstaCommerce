package com.instacommerce.riderfleet.controller;

import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.dto.request.CreateShiftRequest;
import com.instacommerce.riderfleet.dto.request.LocationUpdateRequest;
import com.instacommerce.riderfleet.dto.request.UpdateShiftRequest;
import com.instacommerce.riderfleet.dto.response.EarningsSummaryResponse;
import com.instacommerce.riderfleet.dto.response.RiderResponse;
import com.instacommerce.riderfleet.dto.response.ShiftResponse;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.service.RiderAvailabilityService;
import com.instacommerce.riderfleet.service.RiderEarningsService;
import com.instacommerce.riderfleet.service.RiderService;
import com.instacommerce.riderfleet.service.RiderShiftService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/riders", "/api/riders"})
public class RiderController {
    private final RiderService riderService;
    private final RiderAvailabilityService availabilityService;
    private final RiderEarningsService earningsService;
    private final RiderAvailabilityRepository availabilityRepository;
    private final RiderShiftService shiftService;

    public RiderController(RiderService riderService,
                           RiderAvailabilityService availabilityService,
                           RiderEarningsService earningsService,
                           RiderAvailabilityRepository availabilityRepository) {
        this(riderService, availabilityService, earningsService, availabilityRepository, null);
    }

    @Autowired
    public RiderController(RiderService riderService,
                           RiderAvailabilityService availabilityService,
                           RiderEarningsService earningsService,
                           RiderAvailabilityRepository availabilityRepository,
                           RiderShiftService shiftService) {
        this.riderService = riderService;
        this.availabilityService = availabilityService;
        this.earningsService = earningsService;
        this.availabilityRepository = availabilityRepository;
        this.shiftService = shiftService;
    }

    @PostMapping("/{id}/availability")
    public ResponseEntity<Void> toggleAvailability(@PathVariable UUID id,
                                                    @RequestParam boolean available) {
        assertRiderAccess(id);
        availabilityService.toggleAvailability(id, available);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/location")
    public ResponseEntity<Void> updateLocation(@PathVariable UUID id,
                                                @Valid @RequestBody LocationUpdateRequest request) {
        assertRiderAccess(id);
        availabilityService.updateLocation(id, request.lat(), request.lng());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/available")
    @PreAuthorize("hasAnyRole('INTERNAL', 'INTERNAL_SERVICE', 'ADMIN')")
    public ResponseEntity<List<RiderAvailability>> getAvailableRiders(@RequestParam UUID storeId) {
        List<RiderAvailability> available = availabilityRepository.findByIsAvailableTrueAndStoreId(storeId);
        return ResponseEntity.ok(available);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RiderResponse> getRider(@PathVariable UUID id) {
        assertRiderAccess(id);
        return ResponseEntity.ok(riderService.getRider(id));
    }

    @GetMapping("/{id}/earnings")
    public ResponseEntity<EarningsSummaryResponse> getEarnings(@PathVariable UUID id,
                                                                @RequestParam Instant from,
                                                                @RequestParam Instant to) {
        assertRiderAccess(id);
        return ResponseEntity.ok(earningsService.getEarningsSummary(id, from, to));
    }

    @PostMapping("/{id}/shifts")
    public ResponseEntity<ShiftResponse> createShift(@PathVariable UUID id,
                                                     @Valid @RequestBody CreateShiftRequest request) {
        assertRiderAccess(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftService.createShift(id, request));
    }

    @GetMapping("/{id}/shifts")
    public ResponseEntity<List<ShiftResponse>> listShifts(@PathVariable UUID id,
                                                          @RequestParam(required = false) String status) {
        assertRiderAccess(id);
        return ResponseEntity.ok(shiftService.listShifts(id, status));
    }

    @PatchMapping("/{id}/shifts/{shiftId}")
    public ResponseEntity<ShiftResponse> updateShift(@PathVariable UUID id,
                                                     @PathVariable UUID shiftId,
                                                     @Valid @RequestBody UpdateShiftRequest request) {
        assertRiderAccess(id);
        return ResponseEntity.ok(shiftService.updateShift(id, shiftId, request));
    }

    private void assertRiderAccess(UUID riderId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        if (hasRole(authentication, "ROLE_ADMIN")
            || hasRole(authentication, "ROLE_INTERNAL")
            || hasRole(authentication, "ROLE_INTERNAL_SERVICE")) {
            return;
        }
        if (!riderId.toString().equals(authentication.getName())) {
            throw new AccessDeniedException("Rider access denied");
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
