package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.pricing.config.QuoteTokenProperties;
import com.instacommerce.pricing.consumer.WarehouseEventConsumer;
import com.instacommerce.pricing.domain.PriceQuote;
import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.dto.request.CartItem;
import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.repository.PriceQuoteRepository;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import com.instacommerce.pricing.repository.PromotionRedemptionRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WarehouseSurgePricingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;
    private StoreSurgeStateStore stateStore;
    private WarehouseEventConsumer consumer;

    @BeforeEach
    void setUp() {
        currentInstant = new AtomicReference<>(Instant.parse("2026-03-31T10:00:00Z"));
        mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
        stateStore = new StoreSurgeStateStore(Duration.ofMinutes(10), 4, mutableClock);
        consumer = new WarehouseEventConsumer(objectMapper, stateStore);
    }

    @Test
    @DisplayName("WarehouseEventConsumer handles StoreSurgeModeChanged, StoreCapacityThrottled, and StoreCapacityPaused")
    void consumerUpdatesStoreSurgeStateFromWarehouseEvents() throws Exception {
        UUID storeId = UUID.randomUUID();

        String surgeEnabledJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "StoreSurgeModeChanged",
                "aggregateId", storeId.toString(),
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "surgeModeEnabled", true,
                        "etaExtensionMinutes", 15,
                        "thresholdPercent", 85)));
        consumer.onWarehouseEvent(new ConsumerRecord<>(TopicNames.WAREHOUSE_EVENTS, 0, 1L, storeId.toString(), surgeEnabledJson));

        Optional<StoreSurgeStateStore.StoreSurgeState> stateAfterSurge = stateStore.getActiveState(storeId);
        assertThat(stateAfterSurge).isPresent();
        assertThat(stateAfterSurge.get().surgeModeEnabled()).isTrue();
        assertThat(stateAfterSurge.get().surgeMultiplier()).isEqualByComparingTo("1.25");

        String throttledJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "StoreCapacityThrottled",
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "currentPending", 96,
                        "maxConcurrent", 100,
                        "surgeMode", true,
                        "etaExtensionMinutes", 20)));
        consumer.onWarehouseEvent(new ConsumerRecord<>(TopicNames.WAREHOUSE_EVENTS, 0, 2L, storeId.toString(), throttledJson));

        Optional<StoreSurgeStateStore.StoreSurgeState> stateAfterThrottle = stateStore.getActiveState(storeId);
        assertThat(stateAfterThrottle).isPresent();
        assertThat(stateAfterThrottle.get().throttled()).isTrue();
        assertThat(stateAfterThrottle.get().paused()).isFalse();
        assertThat(stateAfterThrottle.get().surgeMultiplier()).isEqualByComparingTo("1.50");

        String pausedJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "StoreCapacityPaused",
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "currentPending", 100,
                        "maxConcurrent", 100)));
        consumer.onWarehouseEvent(new ConsumerRecord<>(TopicNames.WAREHOUSE_EVENTS, 0, 3L, storeId.toString(), pausedJson));

        Optional<StoreSurgeStateStore.StoreSurgeState> stateAfterPause = stateStore.getActiveState(storeId);
        assertThat(stateAfterPause).isPresent();
        assertThat(stateAfterPause.get().paused()).isTrue();
        assertThat(stateAfterPause.get().throttled()).isTrue();
        assertThat(stateAfterPause.get().surgeMultiplier()).isEqualByComparingTo("1.60");

        String surgeDisabledJson = objectMapper.writeValueAsString(Map.of(
                "eventType", "StoreSurgeModeChanged",
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "surgeModeEnabled", false,
                        "etaExtensionMinutes", 0,
                        "thresholdPercent", 90)));
        consumer.onWarehouseEvent(new ConsumerRecord<>(TopicNames.WAREHOUSE_EVENTS, 0, 4L, storeId.toString(), surgeDisabledJson));

        assertThat(stateStore.getActiveState(storeId)).isEmpty();
    }

    @Test
    @DisplayName("StoreSurgeStateStore enforces TTL expiry and bounded capacity eviction")
    void storeEnforcesTtlAndMaxEntries() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        UUID s4 = UUID.randomUUID();
        UUID s5 = UUID.randomUUID();

        stateStore.recordSurgeModeChanged(s1, true, new BigDecimal("1.20"), 10);
        currentInstant.updateAndGet(t -> t.plusSeconds(1));
        stateStore.recordSurgeModeChanged(s2, true, new BigDecimal("1.30"), 10);
        currentInstant.updateAndGet(t -> t.plusSeconds(1));
        stateStore.recordSurgeModeChanged(s3, true, new BigDecimal("1.40"), 10);
        currentInstant.updateAndGet(t -> t.plusSeconds(1));
        stateStore.recordSurgeModeChanged(s4, true, new BigDecimal("1.50"), 10);
        assertThat(stateStore.size()).isEqualTo(4);

        currentInstant.updateAndGet(t -> t.plusSeconds(1));
        stateStore.recordSurgeModeChanged(s5, true, new BigDecimal("1.60"), 10);
        assertThat(stateStore.size()).isEqualTo(4);
        assertThat(stateStore.getActiveState(s1)).isEmpty();
        assertThat(stateStore.getActiveState(s5)).isPresent();

        currentInstant.updateAndGet(t -> t.plus(Duration.ofMinutes(11)));
        assertThat(stateStore.getActiveState(s5)).isEmpty();
        assertThat(stateStore.size()).isZero();
    }

    @Test
    @DisplayName("PricingService applies live warehouse surge events and falls back cleanly when live state expires or is absent")
    void pricingServiceAppliesLiveWarehouseSurgeAndFallsBackCleanly() throws Exception {
        PriceRuleRepository ruleRepo = mock(PriceRuleRepository.class);
        PromotionRepository promoRepo = mock(PromotionRepository.class);
        PromotionRedemptionRepository redemptionRepo = mock(PromotionRedemptionRepository.class);
        PromotionService promoService = new PromotionService(promoRepo, mock(OutboxService.class), redemptionRepo);
        PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);
        QuoteTokenProperties quoteProps = new QuoteTokenProperties();
        quoteProps.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
        QuoteTokenService quoteTokenService = new QuoteTokenService(quoteRepo, quoteProps);

        SurgePricingService surgePricingService = new SurgePricingService(stateStore);
        PricingService pricingService = new PricingService(
                ruleRepo,
                promoService,
                mock(CouponService.class),
                quoteTokenService,
                new DiscountCalculator(),
                new TaxCalculationService(),
                surgePricingService,
                stateStore);

        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PriceRule rule = new PriceRule();
        rule.setProductId(productId);
        rule.setBasePriceCents(2000L);
        rule.setMultiplier(BigDecimal.ONE);

        when(ruleRepo.findActiveRuleByProductId(eq(productId), any())).thenReturn(Optional.of(rule));
        when(ruleRepo.findActiveRulesByProductIds(anyCollection(), any())).thenReturn(List.of(rule));
        when(promoRepo.findActivePromotions(any())).thenReturn(List.of());
        when(redemptionRepo.countByUserIdAndPromotionIds(any(), anyCollection())).thenReturn(List.of());
        when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
            PriceQuote q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            return q;
        });

        PriceCalculationRequest cartReq = new PriceCalculationRequest(
                List.of(new CartItem(productId, 2)),
                UUID.randomUUID(),
                null,
                storeId);

        PriceCalculationResponse baselineResponse = pricingService.calculateCartPrice(cartReq);
        assertThat(baselineResponse.subtotalCents()).isEqualTo(4000L);
        assertThat(baselineResponse.deliveryFeeCents()).isZero();
        assertThat(baselineResponse.totalCents()).isEqualTo(4000L);
        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(2000L);

        consumer.handleEvent(Map.of(
                "eventType", "StoreCapacityThrottled",
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "currentPending", 95,
                        "maxConcurrent", 100,
                        "surgeMode", true,
                        "etaExtensionMinutes", 15)));

        PriceCalculationResponse throttledResponse = pricingService.calculateCartPrice(cartReq);
        assertThat(throttledResponse.subtotalCents()).isEqualTo(4000L);
        assertThat(throttledResponse.deliveryFeeCents()).isEqualTo(4500L);
        assertThat(throttledResponse.totalCents()).isEqualTo(8500L);
        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(3000L);

        consumer.handleEvent(Map.of(
                "eventType", "StoreCapacityPaused",
                "payload", Map.of(
                        "storeId", storeId.toString(),
                        "currentPending", 100,
                        "maxConcurrent", 100)));

        PriceCalculationResponse pausedResponse = pricingService.calculateCartPrice(cartReq);
        assertThat(pausedResponse.deliveryFeeCents()).isEqualTo(4800L);
        assertThat(pausedResponse.totalCents()).isEqualTo(8800L);
        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(3200L);

        currentInstant.updateAndGet(t -> t.plus(Duration.ofMinutes(15)));

        PriceCalculationResponse expiredResponse = pricingService.calculateCartPrice(cartReq);
        assertThat(expiredResponse.deliveryFeeCents()).isZero();
        assertThat(expiredResponse.totalCents()).isEqualTo(4000L);
        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(2000L);
    }
}
