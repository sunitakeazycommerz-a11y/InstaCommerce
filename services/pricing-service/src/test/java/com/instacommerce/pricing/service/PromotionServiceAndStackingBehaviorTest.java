package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.pricing.consumer.CatalogEventConsumer;
import com.instacommerce.pricing.domain.PriceQuote;
import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.domain.PromotionRedemption;
import com.instacommerce.pricing.dto.request.CartItem;
import com.instacommerce.pricing.dto.request.CreatePromotionRequest;
import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.request.UpdatePromotionRequest;
import com.instacommerce.pricing.dto.response.BatchPriceResponse;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.exception.InvalidPromotionException;
import com.instacommerce.pricing.repository.PriceQuoteRepository;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import com.instacommerce.pricing.repository.PromotionRedemptionRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import com.instacommerce.pricing.config.QuoteTokenProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PromotionServiceAndStackingBehaviorTest {

    @Test
    @DisplayName("Exclusive promotion prevents coupon stacking and clears appliedCoupon when promo yields higher discount")
    void exclusivePromotionWinsOverLowerCoupon() {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        PromotionRedemptionRepository promoRedemptionRepo = mock(PromotionRedemptionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        PromotionService promoService = new PromotionService(promoRepo, outboxService, promoRedemptionRepo);
        CouponService couponService = mock(CouponService.class);
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);

        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        quoteProps.setTtlSeconds(300);
        QuoteTokenService quoteTokenService = new QuoteTokenService(quoteRepo, quoteProps);

        PricingService pricingService = new PricingService(
                ruleRepo, new ProductPriceLookupService(ruleRepo), promoService, couponService, quoteTokenService,
                new DiscountCalculator(), new TaxCalculationService(), null);

        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PriceRule rule = new PriceRule();
        rule.setProductId(productId);
        rule.setBasePriceCents(10_000L);
        rule.setMultiplier(BigDecimal.ONE);
        rule.setEffectiveFrom(now.minusSeconds(60));
        when(ruleRepo.findActiveRuleByProductId(any(), any())).thenReturn(Optional.of(rule));

        Promotion exclusivePromo = new Promotion();
        exclusivePromo.setId(UUID.randomUUID());
        exclusivePromo.setName("FLASH_EXCLUSIVE_4000");
        exclusivePromo.setDiscountType("FIXED_AMOUNT");
        exclusivePromo.setDiscountValue(BigDecimal.valueOf(4000));
        exclusivePromo.setMinOrderCents(1000L);
        exclusivePromo.setStartAt(now.minusSeconds(60));
        exclusivePromo.setEndAt(now.plusSeconds(300));
        exclusivePromo.setActive(true);
        exclusivePromo.setStackableWithCoupon(false);
        when(promoRepo.findActivePromotions(any())).thenReturn(List.of(exclusivePromo));
        when(promoRedemptionRepo.countByUserIdAndPromotionIds(any(), anyCollection())).thenReturn(List.of());
        when(couponService.validateCoupon("SAVE500", userId, 10_000L))
                .thenReturn(new CouponService.CouponValidationResult(500L, "SAVE500"));
        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            return q;
        });

        PriceCalculationResponse response = pricingService.calculateCartPrice(
                new PriceCalculationRequest(List.of(new CartItem(productId, 1)), userId, "SAVE500"));

        assertThat(response.discountCents()).isEqualTo(4000L);
        assertThat(response.appliedPromotions()).containsExactly("FLASH_EXCLUSIVE_4000");
        assertThat(response.appliedPromotionDetails()).containsExactly(
                new PriceCalculationResponse.AppliedPromotionDetail(
                        exclusivePromo.getId(), "FLASH_EXCLUSIVE_4000", 4000L));
        assertThat(response.appliedCoupon()).isNull();
        assertThat(response.couponDiscountCents()).isZero();
    }

    @Test
    @DisplayName("Exclusive promotion yields to higher-value coupon when coupon saves more")
    void couponWinsOverLowerExclusivePromotion() {
        DiscountCalculator calculator = new DiscountCalculator();
        Promotion exclusivePromo = new Promotion();
        exclusivePromo.setName("EXCLUSIVE_500");
        exclusivePromo.setDiscountType("FIXED_AMOUNT");
        exclusivePromo.setDiscountValue(BigDecimal.valueOf(500));
        exclusivePromo.setMinOrderCents(0);
        exclusivePromo.setStackableWithCoupon(false);

        List<PricedItem> items = List.of(new PricedItem(UUID.randomUUID(), 5000L, 1, 5000L));
        DiscountCalculator.CalculationResult result = calculator.calculateDiscounts(
                items, List.of(exclusivePromo), List.of(), 1500L, null);

        assertThat(result.couponDiscountCents()).isEqualTo(1500L);
        assertThat(result.totalDiscountCents()).isEqualTo(1500L);
        assertThat(result.appliedPromotionNames()).isEmpty();
    }

    @Test
    @DisplayName("DiscountCalculator clamps >100% discounts and caps against gross subtotal")
    void discountCalculatorClampsOverHundredPercent() {
        DiscountCalculator calculator = new DiscountCalculator();
        Promotion crazyPromo = new Promotion();
        crazyPromo.setName("OVER_100");
        crazyPromo.setDiscountType("PERCENTAGE");
        crazyPromo.setDiscountValue(BigDecimal.valueOf(150));
        crazyPromo.setMinOrderCents(0);
        crazyPromo.setMaxDiscountCents(2000L);

        List<PricedItem> items = List.of(new PricedItem(UUID.randomUUID(), 2500L, 1, 2500L));
        DiscountCalculator.CalculationResult result = calculator.calculateDiscounts(
                items, List.of(crazyPromo), List.of(), null, null);

        assertThat(result.totalDiscountCents()).isEqualTo(2000L);
        assertThat(result.netTotalCents()).isEqualTo(500L);
    }

    @Test
    @DisplayName("PromotionService validates inverted windows and negative/over-100% bounds on create")
    void promotionValidationRejectsInvalidBoundaries() {
        PromotionService service = new PromotionService(
                mock(PromotionRepository.class), mock(OutboxService.class), mock(PromotionRedemptionRepository.class));
        Instant now = Instant.now();

        assertThatThrownBy(() -> service.create(new CreatePromotionRequest(
                "BAD_WINDOW", null, "PERCENTAGE", BigDecimal.TEN, 0L, null, now, now, 10)))
                .isInstanceOf(InvalidPromotionException.class)
                .hasMessageContaining("endAt");

        assertThatThrownBy(() -> service.create(new CreatePromotionRequest(
                "OVER_PCT", null, "PERCENTAGE", BigDecimal.valueOf(120), 0L, null, now, now.plusSeconds(60), 10)))
                .isInstanceOf(InvalidPromotionException.class)
                .hasMessageContaining("100");

        assertThatThrownBy(() -> service.create(new CreatePromotionRequest(
                "NEG_CAP", null, "FIXED_AMOUNT", BigDecimal.TEN, 0L, -1L, now, now.plusSeconds(60), 10)))
                .isInstanceOf(InvalidPromotionException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("PromotionService enforces per-user redemption limits with idempotent order replay and rollback")
    void perUserPromotionRedemptionLimitAndReplay() {
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        PromotionRedemptionRepository redemptionRepo = mock(PromotionRedemptionRepository.class);
        PromotionService service = new PromotionService(promoRepo, outboxService, redemptionRepo);

        UUID promoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();
        Instant now = Instant.now();

        Promotion promo = new Promotion();
        promo.setId(promoId);
        promo.setActive(true);
        promo.setStartAt(now.minusSeconds(60));
        promo.setEndAt(now.plusSeconds(600));
        promo.setMaxUses(10);
        promo.setCurrentUses(0);
        promo.setMaxRedemptionsPerUser(1);

        when(promoRepo.findByIdForUpdate(promoId)).thenReturn(Optional.of(promo));
        when(promoRepo.incrementUsage(promoId)).thenReturn(1);
        when(redemptionRepo.findByPromotionIdAndUserIdAndOrderId(promoId, userId, order1))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PromotionRedemption()));
        when(redemptionRepo.countByPromotionIdAndUserId(promoId, userId))
                .thenReturn(0L)
                .thenReturn(1L);

        assertThat(service.recordPromotionUsage(promoId, userId, order1, 500L)).isTrue();
        assertThat(service.recordPromotionUsage(promoId, userId, order1, 500L)).isTrue();
        assertThat(service.recordPromotionUsage(promoId, userId, order2, 500L)).isFalse();

        verify(redemptionRepo).save(any(PromotionRedemption.class));
        verify(redemptionRepo, times(2)).countByPromotionIdAndUserId(promoId, userId);

        service.rollbackPromotionUsage(promoId, userId, order1);
        verify(redemptionRepo).delete(any(PromotionRedemption.class));
        verify(promoRepo).decrementUsage(promoId);
    }

    @Test
    @DisplayName("PricingService enforces flash-sale endAt exclusive boundary and batch rule loading")
    void flashSaleBoundaryAndBatchLoading() {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        PromotionRedemptionRepository redemptionRepo = mock(PromotionRedemptionRepository.class);
        PromotionService promoService = new PromotionService(promoRepo, mock(OutboxService.class), redemptionRepo);
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);
        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        QuoteTokenService quoteTokenService = new QuoteTokenService(quoteRepo, quoteProps);

        SurgePricingService surgeService = new SurgePricingService(null);
        UUID storeId = UUID.randomUUID();
        surgeService.recordStoreSignal(storeId, 0.96, 10, false);

        PricingService pricingService = new PricingService(
                ruleRepo, new ProductPriceLookupService(ruleRepo), promoService, mock(CouponService.class), quoteTokenService,
                new DiscountCalculator(), new TaxCalculationService(), surgeService);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Instant now = Instant.now();

        PriceRule r1 = new PriceRule();
        r1.setProductId(p1);
        r1.setBasePriceCents(1000L);
        r1.setMultiplier(BigDecimal.ONE);
        PriceRule r2 = new PriceRule();
        r2.setProductId(p2);
        r2.setBasePriceCents(2000L);
        r2.setMultiplier(BigDecimal.ONE);
        when(ruleRepo.findActiveRulesByProductIds(anyCollection(), any())).thenReturn(List.of(r1, r2));

        Promotion expiredFlash = new Promotion();
        expiredFlash.setId(UUID.randomUUID());
        expiredFlash.setName("ENDED_FLASH");
        expiredFlash.setDiscountType("FIXED_AMOUNT");
        expiredFlash.setDiscountValue(BigDecimal.valueOf(500));
        expiredFlash.setStartAt(now.minusSeconds(120));
        expiredFlash.setEndAt(now.minusMillis(1));
        expiredFlash.setActive(true);
        when(promoRepo.findActivePromotions(any())).thenReturn(List.of(expiredFlash));
        when(redemptionRepo.countByUserIdAndPromotionIds(any(), anyCollection())).thenReturn(List.of());
        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            return q;
        });

        PriceCalculationResponse response = pricingService.calculateCartPrice(
                new PriceCalculationRequest(
                        List.of(new CartItem(p1, 1), new CartItem(p2, 2)),
                        UUID.randomUUID(),
                        null,
                        storeId));

        assertThat(response.subtotalCents()).isEqualTo(5000L);
        assertThat(response.discountCents()).isZero();
        assertThat(response.deliveryFeeCents()).isGreaterThan(0L);
        assertThat(response.totalCents()).isEqualTo(5000L + response.deliveryFeeCents());
        verify(ruleRepo, never()).findActiveRuleByProductId(any(), any());
    }

    @Test
    @DisplayName("CatalogEventConsumer syncs product basePriceCents and deactivates inactive product price rules")
    void catalogEventConsumerSyncsPriceAndDeactivatesInactive() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        CatalogEventConsumer consumer = new CatalogEventConsumer(objectMapper, ruleRepo, null);

        UUID productId = UUID.randomUUID();
        when(ruleRepo.findActiveRuleByProductId(any(), any())).thenReturn(Optional.empty());

        String createdJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "ProductCreated",
                "payload", Map.of(
                        "id", productId.toString(),
                        "sku", "MILK-1L",
                        "name", "Fresh Milk",
                        "active", true,
                        "basePriceCents", 6500L)));

        consumer.onCatalogEvent(new ConsumerRecord<>("catalog.events", 0, 1L, productId.toString(), createdJson));

        ArgumentCaptor<PriceRule> savedCaptor = ArgumentCaptor.forClass(PriceRule.class);
        verify(ruleRepo).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getBasePriceCents()).isEqualTo(6500L);
        assertThat(savedCaptor.getValue().isActive()).isTrue();

        PriceRule activeRule = savedCaptor.getValue();
        when(ruleRepo.findByProductIdAndActiveTrue(productId)).thenReturn(List.of(activeRule));
        String deactivatedJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "ProductUpdated",
                "payload", Map.of(
                        "id", productId.toString(),
                        "sku", "MILK-1L",
                        "name", "Fresh Milk",
                        "active", false)));

        consumer.onCatalogEvent(new ConsumerRecord<>("catalog.events", 0, 2L, productId.toString(), deactivatedJson));
        assertThat(activeRule.isActive()).isFalse();
        assertThat(activeRule.getEffectiveTo()).isNotNull();
    }

    @Test
    @DisplayName("PromoReservationService evicts expired locks past threshold and allows re-acquisition")
    void promoReservationEvictsExpiredLocks() {
        PromoReservationService reservationService = new PromoReservationService(null);
        for (int i = 0; i < 1030; i++) {
            reservationService.tryAcquire("CODE_" + i, UUID.randomUUID(), 0L);
        }
        UUID user = UUID.randomUUID();
        assertThat(reservationService.tryAcquire("FRESH", user, 300L)).isTrue();
        assertThat(reservationService.tryAcquire("FRESH", user, 300L)).isFalse();
        reservationService.release("FRESH", user);
        assertThat(reservationService.tryAcquire("FRESH", user, 300L)).isTrue();
    }

    @Test
    @DisplayName("Stackable promotion + coupon sets couponDiscountCents and appliedCoupon accurately")
    void stackablePromotionAndCouponExposeCouponDiscountCents() {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        PromotionRedemptionRepository promoRedemptionRepo = mock(PromotionRedemptionRepository.class);
        PromotionService promoService = new PromotionService(promoRepo, mock(OutboxService.class), promoRedemptionRepo);
        CouponService couponService = mock(CouponService.class);
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);

        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        quoteProps.setTtlSeconds(300);
        QuoteTokenService quoteTokenService = new QuoteTokenService(quoteRepo, quoteProps);

        PricingService pricingService = new PricingService(
                ruleRepo, new ProductPriceLookupService(ruleRepo), promoService, couponService, quoteTokenService,
                new DiscountCalculator(), new TaxCalculationService(), null);

        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PriceRule rule = new PriceRule();
        rule.setProductId(productId);
        rule.setBasePriceCents(10_000L);
        rule.setMultiplier(BigDecimal.ONE);
        when(ruleRepo.findActiveRuleByProductId(any(), any())).thenReturn(Optional.of(rule));

        Promotion stackablePromo = new Promotion();
        stackablePromo.setId(UUID.randomUUID());
        stackablePromo.setName("STACKABLE_1000");
        stackablePromo.setDiscountType("FIXED_AMOUNT");
        stackablePromo.setDiscountValue(BigDecimal.valueOf(1000));
        stackablePromo.setMinOrderCents(1000L);
        stackablePromo.setStartAt(now.minusSeconds(60));
        stackablePromo.setEndAt(now.plusSeconds(300));
        stackablePromo.setActive(true);
        stackablePromo.setStackableWithCoupon(true);
        when(promoRepo.findActivePromotions(any())).thenReturn(List.of(stackablePromo));
        when(promoRedemptionRepo.countByUserIdAndPromotionIds(any(), anyCollection())).thenReturn(List.of());
        when(couponService.validateCoupon("SAVE500", userId, 9_000L))
                .thenReturn(new CouponService.CouponValidationResult(500L, "SAVE500"));
        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            return q;
        });

        PriceCalculationResponse response = pricingService.calculateCartPrice(
                new PriceCalculationRequest(List.of(new CartItem(productId, 1)), userId, "SAVE500"));

        assertThat(response.discountCents()).isEqualTo(1500L);
        assertThat(response.couponDiscountCents()).isEqualTo(500L);
        assertThat(response.appliedCoupon()).isEqualTo("SAVE500");
        assertThat(response.appliedPromotions()).containsExactly("STACKABLE_1000");
    }

    @Test
    @DisplayName("calculateBatchPrices returns active SKU prices and unavailableProductIds without 404")
    void calculateBatchPricesReturnsUnavailableIdsInsteadOf404() {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PricingService pricingService = new PricingService(
                ruleRepo,
                new ProductPriceLookupService(ruleRepo),
                mock(PromotionService.class),
                mock(CouponService.class),
                mock(QuoteTokenService.class),
                new DiscountCalculator(),
                new TaxCalculationService(),
                null);

        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        PriceRule activeRule = new PriceRule();
        activeRule.setProductId(activeId);
        activeRule.setBasePriceCents(2500L);
        activeRule.setMultiplier(BigDecimal.ONE);
        when(ruleRepo.findActiveRulesByProductIds(anyCollection(), any())).thenReturn(List.of(activeRule));

        BatchPriceResponse response = pricingService.calculateBatchPrices(List.of(activeId, inactiveId));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productId()).isEqualTo(activeId);
        assertThat(response.items().getFirst().unitPriceCents()).isEqualTo(2500L);
        assertThat(response.unavailableProductIds()).containsExactly(inactiveId);
    }

    @Test
    @DisplayName("CatalogEventConsumer rethrows transient runtime failures and malformed payloads for DLT retry")
    void catalogEventConsumerRethrowsFailuresInsteadOfSwallowing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        CatalogEventConsumer consumer = new CatalogEventConsumer(objectMapper, ruleRepo, null);

        UUID productId = UUID.randomUUID();
        String validJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "ProductCreated",
                "payload", Map.of("id", productId.toString(), "sku", "MILK", "name", "Milk", "active", true, "basePriceCents", 5000L)));
        when(ruleRepo.findActiveRuleByProductId(any(), any())).thenThrow(new IllegalStateException("DB transient failure"));

        assertThatThrownBy(() -> consumer.onCatalogEvent(
                new ConsumerRecord<>("catalog.events", 0, 1L, productId.toString(), validJson)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB transient failure");

        assertThatThrownBy(() -> consumer.onCatalogEvent(
                new ConsumerRecord<>("catalog.events", 0, 2L, productId.toString(), "{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to deserialize");
    }

    @Test
    @DisplayName("Single-item cart loads active PriceRule via batch query instead of skipping loadActiveRules")
    void oneItemCartLoadsActiveRuleViaBatchQuery() {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        PromotionRedemptionRepository promoRedemptionRepo = mock(PromotionRedemptionRepository.class);
        PromotionService promoService = new PromotionService(promoRepo, mock(OutboxService.class), promoRedemptionRepo);
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);
        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        QuoteTokenService quoteTokenService = new QuoteTokenService(quoteRepo, quoteProps);

        PricingService pricingService = new PricingService(
                ruleRepo, new ProductPriceLookupService(ruleRepo), promoService, mock(CouponService.class),
                quoteTokenService, new DiscountCalculator(), new TaxCalculationService(), null);

        UUID productId = UUID.randomUUID();
        PriceRule batchRule = new PriceRule();
        batchRule.setProductId(productId);
        batchRule.setBasePriceCents(3000L);
        batchRule.setMultiplier(BigDecimal.ONE);
        when(ruleRepo.findActiveRulesByProductIds(anyCollection(), any())).thenReturn(List.of(batchRule));
        when(promoRepo.findActivePromotions(any())).thenReturn(List.of());
        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            return q;
        });

        PriceCalculationResponse response = pricingService.calculateCartPrice(
                new PriceCalculationRequest(List.of(new CartItem(productId, 2)), UUID.randomUUID(), null));

        assertThat(response.subtotalCents()).isEqualTo(6000L);
        verify(ruleRepo).findActiveRulesByProductIds(anyCollection(), any());
        verify(ruleRepo, never()).findActiveRuleByProductId(any(), any());
    }

    @Test
    @DisplayName("PromotionService emits PromotionCreated, PromotionUpdated, and PromotionDeactivated outbox events")
    @SuppressWarnings("unchecked")
    void promotionServiceEmitsCanonicalContractOutboxEvents() {
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        PromotionService service = new PromotionService(promoRepo, outboxService, mock(PromotionRedemptionRepository.class));

        UUID promoId = UUID.randomUUID();
        Instant now = Instant.now();
        when(promoRepo.save(any(Promotion.class))).thenAnswer(inv -> {
            Promotion p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(promoId);
            }
            return p;
        });

        service.create(new CreatePromotionRequest(
                "SAVE_NOW", "desc", "FIXED_AMOUNT", BigDecimal.valueOf(500), 1000L, null,
                now.minusSeconds(60), now.plusSeconds(3600), 100));

        ArgumentCaptor<Map<String, Object>> createdPayload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).recordEvent(eq("pricing"), eq(promoId.toString()), eq("PromotionCreated"), createdPayload.capture());
        assertThat(createdPayload.getValue()).containsKeys("id", "name", "discountType", "discountValue", "minOrderCents", "active", "createdAt");

        Promotion existing = new Promotion();
        existing.setId(promoId);
        existing.setName("SAVE_NOW");
        existing.setDiscountType("FIXED_AMOUNT");
        existing.setDiscountValue(BigDecimal.valueOf(500));
        existing.setMinOrderCents(1000L);
        existing.setStartAt(now.minusSeconds(60));
        existing.setEndAt(now.plusSeconds(3600));
        existing.setActive(true);
        when(promoRepo.findById(promoId)).thenReturn(Optional.of(existing));

        service.update(promoId, new UpdatePromotionRequest(
                "SAVE_MORE", null, null, null, null, null, null, null, null, null));
        ArgumentCaptor<Map<String, Object>> updatedPayload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).recordEvent(eq("pricing"), eq(promoId.toString()), eq("PromotionUpdated"), updatedPayload.capture());
        assertThat(updatedPayload.getValue()).containsKeys("id", "name", "active", "updatedAt");

        service.delete(promoId);
        ArgumentCaptor<Map<String, Object>> deactivatedPayload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).recordEvent(eq("pricing"), eq(promoId.toString()), eq("PromotionDeactivated"), deactivatedPayload.capture());
        assertThat(deactivatedPayload.getValue()).containsEntry("active", false);
        assertThat(deactivatedPayload.getValue()).containsKeys("id", "active", "deactivatedAt");
    }

    @Test
    @DisplayName("QuoteTokenService guards null/blank tokens without NPE and enforces userId match")
    void quoteTokenValidationGuardsNullTokenAndUserMismatch() {
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);
        QuoteTokenProperties props = new QuoteTokenProperties();
        props.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        props.setValidationEnabled(true);
        QuoteTokenService tokenService = new QuoteTokenService(quoteRepo, props);

        UUID quoteOwner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(quoteId);
            return q;
        });

        PriceCalculationResponse calc = new PriceCalculationResponse(
                List.of(new PricedItem(UUID.randomUUID(), 1000L, 1, 1000L, "INR")),
                1000L, 0L, 1000L, List.of(), null, null, null);
        QuoteTokenService.QuoteResult issued = tokenService.issueQuote(quoteOwner, calc, null);

        PriceQuote stored = new PriceQuote();
        stored.setId(quoteId);
        stored.setUserId(quoteOwner);
        stored.setSubtotalCents(1000L);
        stored.setDiscountCents(0L);
        stored.setTotalCents(1000L);
        stored.setExpiresAt(Instant.now().plusSeconds(120));
        when(quoteRepo.findById(quoteId)).thenReturn(Optional.of(stored));

        assertThat(tokenService.validate(quoteId, null, 1000L, 1000L, 0L, quoteOwner))
                .satisfies(r -> {
                    assertThat(r.isValid()).isFalse();
                    assertThat(r.reason()).isEqualTo("MISSING_TOKEN");
                });
        assertThat(tokenService.validate(null, issued.quoteToken(), 1000L, 1000L, 0L, quoteOwner))
                .satisfies(r -> {
                    assertThat(r.isValid()).isFalse();
                    assertThat(r.reason()).isEqualTo("MISSING_TOKEN");
                });
        assertThat(tokenService.validate(quoteId, issued.quoteToken(), 1000L, 1000L, 0L, otherUser))
                .satisfies(r -> {
                    assertThat(r.isValid()).isFalse();
                    assertThat(r.reason()).isEqualTo("USER_MISMATCH");
                });
    }
}
