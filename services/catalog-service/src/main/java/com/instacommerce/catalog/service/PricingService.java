package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.PricingRule;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.request.PricingComputeRequest;
import com.instacommerce.catalog.dto.request.PricingItemRequest;
import com.instacommerce.catalog.dto.response.CouponDiscountResponse;
import com.instacommerce.catalog.dto.response.PricingBreakdownResponse;
import com.instacommerce.catalog.dto.response.PricingItemResponse;
import com.instacommerce.catalog.exception.ProductNotFoundException;
import com.instacommerce.catalog.pricing.PricingContext;
import com.instacommerce.catalog.pricing.PricingResult;
import com.instacommerce.catalog.pricing.PricingStrategy;
import com.instacommerce.catalog.repository.ProductRepository;
import com.instacommerce.catalog.repository.PricingRuleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
    private final ProductRepository productRepository;
    private final CouponService couponService;
    private final List<PricingStrategy> strategies;
    private final PricingRuleRepository pricingRuleRepository;

    public PricingService(ProductRepository productRepository,
                          CouponService couponService,
                          List<PricingStrategy> strategies,
                          PricingRuleRepository pricingRuleRepository) {
        this.productRepository = productRepository;
        this.couponService = couponService;
        this.strategies = strategies.stream()
            .sorted(Comparator.comparingInt(PricingStrategy::order))
            .toList();
        this.pricingRuleRepository = pricingRuleRepository;
    }

    @Transactional(readOnly = true)
    public PricingBreakdownResponse compute(PricingComputeRequest request) {
        List<UUID> productIds = request.items().stream()
                .map(PricingItemRequest::productId)
                .toList();
        Map<UUID, Product> productMap = productRepository.findAllById(productIds).stream()
                .filter(Product::isActive)
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, List<PricingRule>> rulesByProduct = pricingRuleRepository
            .findApplicableForProducts(productIds, request.storeId(), null, Instant.now())
            .stream()
            .collect(Collectors.groupingBy(rule -> rule.getProduct().getId(),
                LinkedHashMap::new,
                Collectors.toList()));

        List<PricingItemResponse> items = new ArrayList<>();
        long subtotal = 0;
        String currency = "INR";
        for (PricingItemRequest itemRequest : request.items()) {
            Product product = productMap.get(itemRequest.productId());
            if (product == null) {
                throw new ProductNotFoundException(itemRequest.productId());
            }
            if (product.getCurrency() != null) {
                currency = product.getCurrency();
            }
            List<PricingRule> pricingRules = rulesByProduct.getOrDefault(product.getId(), List.of());
            PricingContext context = new PricingContext(product, request.storeId(), null, request.userId(), pricingRules);
            long currentPrice = product.getBasePriceCents();
            List<String> appliedRules = new ArrayList<>();
            for (PricingStrategy strategy : strategies) {
                PricingResult result = strategy.apply(context, currentPrice);
                currentPrice = result.priceCents();
                if (result.appliedRule() != null && !result.appliedRule().isBlank()) {
                    appliedRules.add(result.appliedRule());
                }
            }
            long lineTotal = currentPrice * itemRequest.quantity();
            subtotal += lineTotal;
            items.add(new PricingItemResponse(
                product.getId(),
                product.getName(),
                itemRequest.quantity(),
                product.getBasePriceCents(),
                currentPrice,
                lineTotal,
                appliedRules));
        }
        CouponDiscountResponse couponDiscount = couponService
            .validateAndCalculate(request.couponCode(), request.userId(), subtotal);
        long discount = couponDiscount == null ? 0 : couponDiscount.discountCents();
        long total = Math.max(subtotal - discount, 0);
        return new PricingBreakdownResponse(items, subtotal, couponDiscount, total, currency);
    }
}
