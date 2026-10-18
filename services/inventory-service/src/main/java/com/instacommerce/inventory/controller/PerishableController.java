package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.dto.request.CreateBatchRequest;
import com.instacommerce.inventory.dto.response.StockBatchResponse;
import com.instacommerce.inventory.service.PerishableLiquidationService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
@RequestMapping
@Validated
public class PerishableController {

    private final PerishableLiquidationService perishableService;

    public PerishableController(PerishableLiquidationService perishableService) {
        this.perishableService = perishableService;
    }

    @PostMapping("/stores/{storeId}/batches")
    public ResponseEntity<StockBatchResponse> createBatch(
            @PathVariable UUID storeId,
            @Valid @RequestBody CreateBatchRequest request) {
        return ResponseEntity.ok(perishableService.createOrUpdateBatch(storeId, request));
    }

    @GetMapping("/stores/{storeId}/batches")
    public ResponseEntity<List<StockBatchResponse>> getBatches(@PathVariable UUID storeId) {
        return ResponseEntity.ok(perishableService.getStoreBatches(storeId));
    }

    @PostMapping("/stores/{storeId}/perishables/trigger-markdowns")
    public ResponseEntity<List<StockBatchResponse>> triggerStoreMarkdowns(
            @PathVariable UUID storeId,
            @RequestParam(required = false, defaultValue = "24") int hoursAhead) {
        return ResponseEntity.ok(perishableService.triggerMarkdownsForStore(storeId, Duration.ofHours(hoursAhead)));
    }

    @PostMapping("/perishables/trigger-markdowns")
    public ResponseEntity<List<StockBatchResponse>> triggerGlobalMarkdowns(
            @RequestParam(required = false, defaultValue = "24") int hoursAhead) {
        return ResponseEntity.ok(perishableService.triggerMarkdownsGlobal(Duration.ofHours(hoursAhead)));
    }
}
