package com.instacommerce.fulfillment.controller;

import com.instacommerce.fulfillment.dto.request.CreateBatchRequest;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.dto.request.MarkPackedRequest;
import com.instacommerce.fulfillment.dto.request.ValidateCompartmentRequest;
import com.instacommerce.fulfillment.dto.response.BatchPickResponse;
import com.instacommerce.fulfillment.dto.response.PickItemResponse;
import com.instacommerce.fulfillment.dto.response.PickTaskResponse;
import com.instacommerce.fulfillment.service.BatchPickingService;
import com.instacommerce.fulfillment.service.PickService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fulfillment")
public class PickController {
    private final PickService pickService;
    private final BatchPickingService batchPickingService;

    public PickController(PickService pickService, BatchPickingService batchPickingService) {
        this.pickService = pickService;
        this.batchPickingService = batchPickingService;
    }

    @GetMapping("/picklist/{storeId}")
    public Page<PickTaskResponse> listPickTasks(@PathVariable String storeId, Pageable pageable) {
        return pickService.listPendingTasks(storeId, pageable);
    }

    @GetMapping("/picklist/{orderId}/items")
    public List<PickItemResponse> listPickItems(@PathVariable UUID orderId) {
        return pickService.listItems(orderId);
    }

    @PostMapping("/picklist/{orderId}/items/{productId}")
    public PickItemResponse markItem(@PathVariable UUID orderId,
                                     @PathVariable UUID productId,
                                     @Valid @RequestBody MarkItemPickedRequest request,
                                     @AuthenticationPrincipal String principal) {
        return pickService.markItem(orderId, productId, request, toUuid(principal));
    }

    @PostMapping("/orders/{orderId}/packed")
    public PickTaskResponse markPacked(@PathVariable UUID orderId,
                                       @Valid @RequestBody(required = false) MarkPackedRequest request,
                                       @AuthenticationPrincipal String principal) {
        String note = request == null ? null : request.note();
        return pickService.markPacked(orderId, toUuid(principal), note);
    }

    @PostMapping("/batches")
    public BatchPickResponse createBatch(@Valid @RequestBody CreateBatchRequest request,
                                         @AuthenticationPrincipal String principal) {
        return batchPickingService.createBatch(request, toUuid(principal));
    }

    @GetMapping("/batches/{batchId}")
    public BatchPickResponse getBatch(@PathVariable UUID batchId) {
        return batchPickingService.getBatch(batchId);
    }

    @PostMapping("/batches/{batchId}/validate-compartment")
    public ResponseEntity<Map<String, Object>> validateCompartment(@PathVariable UUID batchId,
                                                                   @Valid @RequestBody ValidateCompartmentRequest request) {
        batchPickingService.validateCompartment(batchId, request.orderId(), request.scannedBarcode());
        return ResponseEntity.ok(Map.of("batchId", batchId, "valid", true));
    }

    @PostMapping("/batches/{batchId}/complete")
    public BatchPickResponse completeBatch(@PathVariable UUID batchId) {
        return batchPickingService.completeBatch(batchId);
    }

    private UUID toUuid(String principal) {
        return principal == null ? null : UUID.fromString(principal);
    }
}
