package com.instacommerce.pricing.controller;

import com.instacommerce.pricing.dto.request.CreateCouponRequest;
import com.instacommerce.pricing.dto.response.CouponResponse;
import com.instacommerce.pricing.service.CouponService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<List<CouponResponse>> listAll() {
        return ResponseEntity.ok(couponService.listAll());
    }

    @GetMapping("/lookup")
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<CouponResponse> getByCode(@RequestParam String code) {
        return ResponseEntity.ok(couponService.getByCode(code));
    }

    @PostMapping
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest request) {
        CouponResponse response = couponService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @RateLimiter(name = "adminLimiter")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        couponService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
