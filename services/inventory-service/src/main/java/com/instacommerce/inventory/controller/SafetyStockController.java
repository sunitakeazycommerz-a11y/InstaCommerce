package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.dto.request.SafetyStockItemRequest;
import com.instacommerce.inventory.dto.response.SafetyStockResponse;
import com.instacommerce.inventory.service.SafetyStockService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@Validated
public class SafetyStockController {

    private final SafetyStockService safetyStockService;

    public SafetyStockController(SafetyStockService safetyStockService) {
        this.safetyStockService = safetyStockService;
    }

    @PostMapping("/stores/{storeId}/safety-stock/ingest")
    public ResponseEntity<SafetyStockResponse> ingestBuffer(
            @PathVariable UUID storeId,
            @Valid @RequestBody SafetyStockItemRequest request) {
        return ResponseEntity.ok(safetyStockService.ingestBuffer(storeId, request));
    }

    @PostMapping("/stores/{storeId}/safety-stock/ingest-batch")
    public ResponseEntity<List<SafetyStockResponse>> ingestBatch(
            @PathVariable UUID storeId,
            @Valid @RequestBody List<SafetyStockItemRequest> requests) {
        return ResponseEntity.ok(safetyStockService.ingestBatch(storeId, requests));
    }

    @GetMapping("/stores/{storeId}/safety-stock/{productId}")
    public ResponseEntity<SafetyStockResponse> getBuffer(
            @PathVariable UUID storeId,
            @PathVariable UUID productId) {
        return safetyStockService.getBuffer(storeId, productId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/stores/{storeId}/safety-stock")
    public ResponseEntity<List<SafetyStockResponse>> getStoreBuffers(@PathVariable UUID storeId) {
        return ResponseEntity.ok(safetyStockService.getStoreBuffers(storeId));
    }

    @PostMapping("/stores/{storeId}/safety-stock/check-reorders")
    public ResponseEntity<List<SafetyStockResponse>> checkReorders(@PathVariable UUID storeId) {
        return ResponseEntity.ok(safetyStockService.checkStoreReorders(storeId));
    }
}
