package com.instacommerce.pricing.controller;

import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.request.RedeemCouponRequest;
import com.instacommerce.pricing.dto.request.UnredeemCouponRequest;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pricing")
public class PricingController {

    private final PricingService pricingService;
    private final CouponService couponService;

    public PricingController(PricingService pricingService, CouponService couponService) {
        this.pricingService = pricingService;
        this.couponService = couponService;
    }

    @PostMapping("/calculate")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<PriceCalculationResponse> calculateCartPrice(
            @Valid @RequestBody PriceCalculationRequest request) {
        PriceCalculationResponse response = pricingService.calculateCartPrice(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/products/{id}")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<PricedItem> getProductPrice(@PathVariable UUID id) {
        long priceCents = pricingService.calculatePrice(id);
        PricedItem item = new PricedItem(id, priceCents, 1, priceCents);
        return ResponseEntity.ok(item);
    }

    @PostMapping("/coupons/redeem")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> redeemCoupon(@Valid @RequestBody RedeemCouponRequest request) {
        couponService.redeemCoupon(request.code(), request.userId(), request.orderId(), request.discountCents());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coupons/unredeem")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> unredeemCoupon(@Valid @RequestBody UnredeemCouponRequest request) {
        couponService.unredeemCoupon(request.code(), request.userId(), request.orderId());
        return ResponseEntity.ok().build();
    }
}
