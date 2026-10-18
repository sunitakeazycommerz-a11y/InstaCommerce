package com.instacommerce.warehouse.controller;

import com.instacommerce.warehouse.dto.request.OptimizePathRequest;
import com.instacommerce.warehouse.dto.response.OptimizedPathResponse;
import com.instacommerce.warehouse.service.StoreLayoutService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stores/{storeId}/layout")
@Validated
public class StoreLayoutController {

    private final StoreLayoutService layoutService;

    public StoreLayoutController(StoreLayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @PostMapping("/optimize-path")
    public ResponseEntity<OptimizedPathResponse> optimizePath(
            @PathVariable UUID storeId,
            @RequestBody @Valid OptimizePathRequest request) {
        OptimizedPathResponse response = layoutService.optimizePickPath(storeId, request);
        return ResponseEntity.ok(response);
    }
}
