package com.instacommerce.pricing.controller;

import com.instacommerce.pricing.dto.request.CreatePromotionRequest;
import com.instacommerce.pricing.dto.request.UpdatePromotionRequest;
import com.instacommerce.pricing.dto.response.PromotionResponse;
import com.instacommerce.pricing.service.PromotionService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/promotions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPromotionController {

    private final PromotionService promotionService;

    public AdminPromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<List<PromotionResponse>> listAll() {
        return ResponseEntity.ok(promotionService.listAll());
    }

    @GetMapping("/{id}")
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<PromotionResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(promotionService.getById(id));
    }

    @PostMapping
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody CreatePromotionRequest request) {
        PromotionResponse response = promotionService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<PromotionResponse> update(@PathVariable UUID id,
                                                    @Valid @RequestBody UpdatePromotionRequest request) {
        return ResponseEntity.ok(promotionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        promotionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
