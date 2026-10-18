package com.instacommerce.warehouse.controller;

import com.instacommerce.warehouse.dto.request.DispatchPackagingRequest;
import com.instacommerce.warehouse.dto.request.StagingTelemetryRequest;
import com.instacommerce.warehouse.dto.response.DispatchPackagingResponse;
import com.instacommerce.warehouse.dto.response.StagingTelemetryResponse;
import com.instacommerce.warehouse.service.StagingTelemetryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stores/{storeId}/staging")
@Validated
public class StagingTelemetryController {

    private final StagingTelemetryService telemetryService;

    public StagingTelemetryController(StagingTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping("/telemetry")
    public ResponseEntity<StagingTelemetryResponse> recordTelemetry(
            @PathVariable UUID storeId,
            @RequestBody @Valid StagingTelemetryRequest request) {
        StagingTelemetryResponse response = telemetryService.recordTelemetry(storeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/telemetry")
    public ResponseEntity<List<StagingTelemetryResponse>> getTelemetry(
            @PathVariable UUID storeId,
            @RequestParam(required = false) String bayId) {
        if (bayId != null && !bayId.isBlank()) {
            return ResponseEntity.ok(telemetryService.getRecentTelemetry(storeId, bayId));
        }
        return ResponseEntity.ok(telemetryService.getAllTelemetryForStore(storeId));
    }

    @PostMapping("/validate-dispatch")
    public ResponseEntity<DispatchPackagingResponse> validateDispatch(
            @PathVariable UUID storeId,
            @RequestBody @Valid DispatchPackagingRequest request) {
        DispatchPackagingResponse response = telemetryService.validateDispatchPackaging(storeId, request);
        return ResponseEntity.ok(response);
    }
}
