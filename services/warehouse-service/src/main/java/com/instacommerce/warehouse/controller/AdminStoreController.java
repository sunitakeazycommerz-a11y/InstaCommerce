package com.instacommerce.warehouse.controller;

import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.dto.request.CreateStoreRequest;
import com.instacommerce.warehouse.dto.request.CreateZoneRequest;
import com.instacommerce.warehouse.dto.request.UpdateZoneRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.service.CapacityService;
import com.instacommerce.warehouse.service.StoreService;
import com.instacommerce.warehouse.service.ZoneService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/stores")
public class AdminStoreController {

    private final StoreService storeService;
    private final CapacityService capacityService;
    private final ZoneService zoneService;

    public AdminStoreController(StoreService storeService,
                                CapacityService capacityService,
                                ZoneService zoneService) {
        this.storeService = storeService;
        this.capacityService = capacityService;
        this.zoneService = zoneService;
    }

    @PostMapping
    public ResponseEntity<StoreResponse> createStore(@RequestBody @Valid CreateStoreRequest request) {
        StoreResponse response = storeService.createStore(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreResponse> getStore(@PathVariable UUID id) {
        return ResponseEntity.ok(storeService.getStore(id));
    }

    @GetMapping
    public ResponseEntity<List<StoreResponse>> listStores(
            @RequestParam(required = false) StoreStatus status) {
        if (status != null) {
            return ResponseEntity.ok(storeService.findByStatus(status));
        }
        return ResponseEntity.ok(storeService.findByStatus(StoreStatus.ACTIVE));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<StoreResponse> updateStoreStatus(
            @PathVariable UUID id,
            @RequestParam StoreStatus status) {
        return ResponseEntity.ok(storeService.updateStoreStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStore(@PathVariable UUID id) {
        storeService.deleteStore(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/capacity/increment")
    public ResponseEntity<Boolean> incrementCapacity(@PathVariable UUID id) {
        boolean success = capacityService.incrementOrderCount(id, LocalDateTime.now());
        return ResponseEntity.ok(success);
    }

    @GetMapping("/{id}/can-accept")
    public ResponseEntity<Boolean> canAcceptOrder(@PathVariable UUID id) {
        boolean canAccept = capacityService.canAcceptOrder(id, LocalDateTime.now());
        return ResponseEntity.ok(canAccept);
    }

    @GetMapping("/{id}/zones")
    public ResponseEntity<List<StoreResponse.ZoneResponse>> listZones(@PathVariable UUID id) {
        return ResponseEntity.ok(zoneService.getStoreZones(id));
    }

    @GetMapping("/{id}/zones/{zoneId}")
    public ResponseEntity<StoreResponse.ZoneResponse> getZone(
            @PathVariable UUID id,
            @PathVariable UUID zoneId) {
        return ResponseEntity.ok(zoneService.getZone(id, zoneId));
    }

    @PostMapping("/{id}/zones")
    public ResponseEntity<StoreResponse.ZoneResponse> createZone(
            @PathVariable UUID id,
            @RequestBody @Valid CreateZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(zoneService.createZone(id, request));
    }

    @PutMapping("/{id}/zones/{zoneId}")
    public ResponseEntity<StoreResponse.ZoneResponse> updateZone(
            @PathVariable UUID id,
            @PathVariable UUID zoneId,
            @RequestBody @Valid UpdateZoneRequest request) {
        return ResponseEntity.ok(zoneService.updateZone(id, zoneId, request));
    }

    @DeleteMapping("/{id}/zones/{zoneId}")
    public ResponseEntity<Void> deleteZone(
            @PathVariable UUID id,
            @PathVariable UUID zoneId) {
        zoneService.deleteZone(id, zoneId);
        return ResponseEntity.noContent().build();
    }
}
