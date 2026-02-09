package com.instacommerce.inventory.controller;

import com.instacommerce.inventory.dto.request.StockAdjustRequest;
import com.instacommerce.inventory.dto.request.StockCheckRequest;
import com.instacommerce.inventory.dto.response.StockCheckItemResponse;
import com.instacommerce.inventory.dto.response.StockCheckResponse;
import com.instacommerce.inventory.service.InventoryService;
import com.instacommerce.inventory.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/inventory")
public class StockController {
    private final InventoryService inventoryService;
    private final RateLimitService rateLimitService;

    public StockController(InventoryService inventoryService, RateLimitService rateLimitService) {
        this.inventoryService = inventoryService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/check")
    public StockCheckResponse check(@Valid @RequestBody StockCheckRequest request,
                                    HttpServletRequest httpRequest) {
        if (!rateLimitService.tryAcquire(extractClientIp(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
        return inventoryService.checkAvailability(request);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public StockCheckItemResponse adjust(@Valid @RequestBody StockAdjustRequest request) {
        return inventoryService.adjustStock(request);
    }
}
