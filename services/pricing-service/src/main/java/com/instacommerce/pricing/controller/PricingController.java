package com.instacommerce.pricing.controller;

import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.request.QuoteValidationRequest;
import com.instacommerce.pricing.dto.request.RedeemCouponRequest;
import com.instacommerce.pricing.dto.request.RedeemPromotionRequest;
import com.instacommerce.pricing.dto.request.RollbackCouponUsageRequest;
import com.instacommerce.pricing.dto.request.RollbackPromotionUsageRequest;
import com.instacommerce.pricing.dto.request.TaxCalculationRequest;
import com.instacommerce.pricing.dto.request.UnredeemCouponRequest;
import com.instacommerce.pricing.dto.response.BatchPriceResponse;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.dto.response.QuoteValidationResponse;
import com.instacommerce.pricing.dto.response.TaxCalculationResponse;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import com.instacommerce.pricing.service.PromotionService;
import com.instacommerce.pricing.service.QuoteTokenService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping({"/pricing", "/api/pricing", "/api/v1/pricing"})
public class PricingController {

    private final PricingService pricingService;
    private final CouponService couponService;
    private final QuoteTokenService quoteTokenService;
    private final PromotionService promotionService;

    public PricingController(PricingService pricingService,
                             CouponService couponService,
                             QuoteTokenService quoteTokenService,
                             PromotionService promotionService) {
        this.pricingService = pricingService;
        this.couponService = couponService;
        this.quoteTokenService = quoteTokenService;
        this.promotionService = promotionService;
    }

    @PostMapping("/calculate")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<PriceCalculationResponse> calculateCartPrice(
            @Valid @RequestBody PriceCalculationRequest request) {
        PriceCalculationResponse response = pricingService.calculateCartPrice(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tax")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<TaxCalculationResponse> calculateTax(
            @Valid @RequestBody TaxCalculationRequest request) {
        return ResponseEntity.ok(pricingService.calculateTax(request));
    }

    @GetMapping({"/products/{id}", "/api/v1/prices/{id}"})
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<PricedItem> getProductPrice(@PathVariable UUID id) {
        long priceCents = pricingService.calculatePrice(id);
        PricedItem item = new PricedItem(id, priceCents, 1, priceCents, "INR");
        return ResponseEntity.ok(item);
    }

    @PostMapping("/products/batch")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<BatchPriceResponse> getBatchProductPrices(
            @RequestBody @NotEmpty @Size(max = 200) List<UUID> productIds) {
        return ResponseEntity.ok(pricingService.calculateBatchPrices(productIds));
    }

    @PostMapping("/coupons/redeem")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> redeemCoupon(@Valid @RequestBody RedeemCouponRequest request) {
        couponService.redeemCoupon(request.code(), request.userId(), request.orderId(), request.discountCents());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coupons/unredeem")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> unredeemCoupon(@Valid @RequestBody UnredeemCouponRequest request) {
        couponService.unredeemCoupon(request.code(), request.userId(), request.orderId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coupons/rollback-usage")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> rollbackUsage(@Valid @RequestBody RollbackCouponUsageRequest request) {
        couponService.rollbackUsage(request.code(), request.userId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/promotions/redeem")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> redeemPromotion(@Valid @RequestBody RedeemPromotionRequest request) {
        boolean recorded = promotionService.recordPromotionUsage(
                request.promotionId(), request.userId(), request.orderId(), request.discountCents());
        if (!recorded) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/promotions/rollback")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<Void> rollbackPromotion(@Valid @RequestBody RollbackPromotionUsageRequest request) {
        promotionService.rollbackPromotionUsage(request.promotionId(), request.userId(), request.orderId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/quotes/validate")
    @PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
    @RateLimiter(name = "pricingLimiter")
    public ResponseEntity<QuoteValidationResponse> validateQuote(
            @Valid @RequestBody QuoteValidationRequest request) {
        QuoteTokenService.ValidationResult result = quoteTokenService.validate(
                request.quoteId(),
                request.quoteToken(),
                request.totalCents(),
                request.subtotalCents(),
                request.discountCents());

        QuoteValidationResponse response = new QuoteValidationResponse(result.isValid(), result.reason());

        if (result.isValid()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(409).body(response);
    }
}
