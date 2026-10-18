package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.service.StockReconciliationService;
import com.instacommerce.inventory.service.StockReconciliationService.MissingItemAlert;
import com.instacommerce.inventory.service.StockReconciliationService.PhysicalStockCount;
import com.instacommerce.inventory.service.StockReconciliationService.ReconciliationSummary;
import com.instacommerce.inventory.service.StockReconciliationService.CycleCountTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
@Validated
public class StockReconciliationController {

    private final StockReconciliationService reconciliationService;

    public StockReconciliationController(StockReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    public record RunReconciliationRequest(
        @NotNull UUID storeId,
        @NotNull List<PhysicalStockCount> physicalCounts
    ) {}

    public record UnfreezeRequest(
        @NotNull UUID productId,
        @NotNull UUID storeId,
        int verifiedPhysicalQty
    ) {}

    @PostMapping("/reconciliation/run")
    public ResponseEntity<ReconciliationSummary> runReconciliation(@Valid @RequestBody RunReconciliationRequest request) {
        ReconciliationSummary summary = reconciliationService.reconcileStock(request.storeId(), request.physicalCounts());
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/alerts/missing-item")
    public ResponseEntity<CycleCountTask> reportMissingItem(@Valid @RequestBody MissingItemAlert alert) {
        CycleCountTask task = reconciliationService.reportMissingItem(alert);
        return ResponseEntity.ok(task);
    }

    @PostMapping("/audit/unfreeze")
    public ResponseEntity<Void> unfreezeStock(@Valid @RequestBody UnfreezeRequest request) {
        reconciliationService.unfreezeStock(request.productId(), request.storeId(), request.verifiedPhysicalQty());
        return ResponseEntity.ok().build();
    }
}
