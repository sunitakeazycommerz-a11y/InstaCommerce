package com.instacommerce.warehouse.controller;

import com.instacommerce.warehouse.dto.request.BinAssignmentRequest;
import com.instacommerce.warehouse.dto.response.BinResponse;
import com.instacommerce.warehouse.service.BinService;
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
@RequestMapping("/stores/{storeId}/bins")
@Validated
public class BinController {

    private final BinService binService;

    public BinController(BinService binService) {
        this.binService = binService;
    }

    @PostMapping("/assign")
    public ResponseEntity<BinResponse> assignItem(
            @PathVariable UUID storeId,
            @RequestBody @Valid BinAssignmentRequest request) {
        BinResponse response = binService.assignItem(storeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<BinResponse>> getBinsForStore(@PathVariable UUID storeId) {
        return ResponseEntity.ok(binService.getBinsForStore(storeId));
    }

    @GetMapping("/by-sku")
    public ResponseEntity<List<BinResponse>> findBinsBySku(
            @PathVariable UUID storeId,
            @RequestParam String sku) {
        return ResponseEntity.ok(binService.findBinsBySku(storeId, sku));
    }
}
