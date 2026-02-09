package com.instacommerce.warehouse.controller;

import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.service.CapacityService;
import com.instacommerce.warehouse.service.StoreService;
import com.instacommerce.warehouse.service.ZoneService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stores")
@Validated
public class StoreController {

    private final StoreService storeService;
    private final CapacityService capacityService;
    private final ZoneService zoneService;

    public StoreController(StoreService storeService,
                           CapacityService capacityService,
                           ZoneService zoneService) {
        this.storeService = storeService;
        this.capacityService = capacityService;
        this.zoneService = zoneService;
    }

    @GetMapping("/nearest")
    public ResponseEntity<List<StoreResponse>> findNearestStores(
            @RequestParam @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @RequestParam(required = false) Double radiusKm) {
        List<StoreResponse> stores = storeService.findNearestStores(lat, lng, radiusKm);
        return ResponseEntity.ok(stores);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreResponse> getStore(@PathVariable UUID id) {
        return ResponseEntity.ok(storeService.getStore(id));
    }

    @GetMapping("/{id}/capacity")
    public ResponseEntity<CapacityResponse> getStoreCapacity(@PathVariable UUID id) {
        CapacityResponse capacity = capacityService.getStoreCapacity(id, LocalDateTime.now());
        return ResponseEntity.ok(capacity);
    }

    @GetMapping("/{id}/open")
    public ResponseEntity<Boolean> isStoreOpen(@PathVariable UUID id) {
        return ResponseEntity.ok(storeService.isStoreOpen(id, LocalDateTime.now()));
    }

    @GetMapping("/by-pincode")
    public ResponseEntity<List<UUID>> findStoresByPincode(@RequestParam String pincode) {
        return ResponseEntity.ok(zoneService.mapPincodeToStoreIds(pincode));
    }

    @GetMapping("/by-city")
    public ResponseEntity<List<StoreResponse>> findStoresByCity(@RequestParam String city) {
        return ResponseEntity.ok(storeService.findByCity(city));
    }

    @GetMapping("/{id}/zones")
    public ResponseEntity<List<StoreResponse.ZoneResponse>> getStoreZones(@PathVariable UUID id) {
        return ResponseEntity.ok(zoneService.getStoreZones(id));
    }

    @GetMapping("/{id}/delivery-radius")
    public ResponseEntity<BigDecimal> getDeliveryRadius(@PathVariable UUID id) {
        return ResponseEntity.ok(zoneService.getDeliveryRadius(id));
    }
}
