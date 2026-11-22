package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.pricing.config.QuoteTokenProperties;
import com.instacommerce.pricing.domain.PriceQuote;
import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.dto.request.CartItem;
import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.repository.PriceQuoteRepository;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import com.instacommerce.pricing.repository.PromotionRedemptionRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = PricingCacheProxyIntegrationTest.Config.class)
class PricingCacheProxyIntegrationTest {

    @Configuration
    @EnableCaching
    @Import({
        ProductPriceLookupService.class,
        PricingService.class,
        SurgePricingService.class
    })
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("productPrices", "activePromotions", "priceRules");
        }

        @Bean
        PriceRuleRepository priceRuleRepository() {
            return mock(PriceRuleRepository.class);
        }

        @Bean
        PromotionService promotionService() {
            PromotionRepository promoRepo = mock(PromotionRepository.class);
            PromotionRedemptionRepository redemptionRepo = mock(PromotionRedemptionRepository.class);
            when(promoRepo.findActivePromotions(any())).thenReturn(List.of());
            return new PromotionService(promoRepo, mock(OutboxService.class), redemptionRepo);
        }

        @Bean
        CouponService couponService() {
            return mock(CouponService.class);
        }

        @Bean
        QuoteTokenService quoteTokenService() {
            PriceQuoteRepository quoteRepo = mock(PriceQuoteRepository.class);
            when(quoteRepo.save(any(PriceQuote.class))).thenAnswer(inv -> {
                PriceQuote q = inv.getArgument(0);
                q.setId(UUID.randomUUID());
                return q;
            });
            QuoteTokenProperties props = new QuoteTokenProperties();
            props.setHmacSecret("test-pricing-hmac-secret-32-bytes-minimum");
            return new QuoteTokenService(quoteRepo, props);
        }

        @Bean
        DiscountCalculator discountCalculator() {
            return new DiscountCalculator();
        }

        @Bean
        TaxCalculationService taxCalculationService() {
            return new TaxCalculationService();
        }

        @Bean
        StoreSurgeStateStore storeSurgeStateStore() {
            return new StoreSurgeStateStore(Duration.ofMinutes(15), 64, Clock.systemUTC());
        }
    }

    @Autowired
    private PricingService pricingService;

    @Autowired
    private ProductPriceLookupService productPriceLookupService;

    @Autowired
    private PriceRuleRepository priceRuleRepository;

    @Autowired
    private StoreSurgeStateStore storeSurgeStateStore;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("productPrices");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("calculatePrice(productId, storeId) and calculateCartPrice hit @Cacheable productPrices AOP proxy")
    void calculatePriceWithStoreIdHitsProductPricesCacheProxy() {
        assertThat(AopUtils.isAopProxy(productPriceLookupService)).isTrue();

        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        storeSurgeStateStore.clear(storeId);

        PriceRule rule = new PriceRule();
        rule.setProductId(productId);
        rule.setBasePriceCents(2000L);
        rule.setMultiplier(BigDecimal.ONE);
        when(priceRuleRepository.findActiveRuleByProductId(eq(productId), any())).thenReturn(Optional.of(rule));

        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(2000L);
        assertThat(pricingService.calculatePrice(productId)).isEqualTo(2000L);

        storeSurgeStateStore.recordSurgeModeChanged(storeId, true, new BigDecimal("1.50"), 15);
        assertThat(pricingService.calculatePrice(productId, storeId)).isEqualTo(3000L);

        var cartResponse = pricingService.calculateCartPrice(
                new PriceCalculationRequest(List.of(new CartItem(productId, 2)), UUID.randomUUID(), null, storeId));
        assertThat(cartResponse.subtotalCents()).isEqualTo(4000L);
        assertThat(cartResponse.deliveryFeeCents()).isEqualTo(4500L);

        verify(priceRuleRepository, times(1)).findActiveRuleByProductId(eq(productId), any());
        Cache cache = cacheManager.getCache("productPrices");
        assertThat(cache).isNotNull();
        assertThat(cache.get(productId, Long.class)).isEqualTo(2000L);
    }
}
