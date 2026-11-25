package com.instacommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.config.KafkaConfig;
import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.request.PricingComputeRequest;
import com.instacommerce.catalog.dto.request.PricingItemRequest;
import com.instacommerce.catalog.dto.response.PageResponse;
import com.instacommerce.catalog.dto.response.PricingBreakdownResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.pricing.BasePriceStrategy;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.PricingRuleRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class CatalogEdgeFixesTest {

    @Test
    @DisplayName("CategoryService returns Jackson-serializable PageResponse for category products")
    void categoryProductsReturnsSerializablePageResponse() throws Exception {
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        CategoryService categoryService = new CategoryService(categoryRepo, productRepo);

        UUID categoryId = UUID.randomUUID();
        Category category = new Category();
        category.setId(categoryId);
        category.setName("Dairy");
        category.setSlug("dairy");

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSku("SKU-MILK-1");
        product.setName("Organic Milk");
        product.setSlug("organic-milk");
        product.setCategory(category);
        product.setBasePriceCents(6500L);
        product.setCurrency("INR");
        product.setActive(true);
        product.setCreatedAt(Instant.now());
        product.setUpdatedAt(Instant.now());

        when(categoryRepo.existsById(categoryId)).thenReturn(true);
        when(productRepo.findByCategory_IdAndIsActiveTrue(eq(categoryId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1));

        PageResponse<ProductResponse> response = categoryService.getProductsByCategory(categoryId, PageRequest.of(0, 500));

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(response);
        PageResponse<ProductResponse> roundTrip = mapper.readValue(json, new TypeReference<>() {});
        assertThat(roundTrip.content()).hasSize(1);
        assertThat(roundTrip.content().getFirst().sku()).isEqualTo("SKU-MILK-1");
    }

    @Test
    @DisplayName("PricingService handles duplicate productId entries in a single batch compute request")
    void pricingComputeHandlesDuplicateProductIds() {
        ProductRepository productRepository = mock(ProductRepository.class);
        CouponService couponService = mock(CouponService.class);
        PricingRuleRepository pricingRuleRepository = mock(PricingRuleRepository.class);
        PricingService pricingService = new PricingService(
                productRepository,
                couponService,
                List.of(new BasePriceStrategy()),
                pricingRuleRepository);

        UUID productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setName("Cold Coffee");
        product.setBasePriceCents(5000L);
        product.setCurrency("INR");
        product.setActive(true);

        when(productRepository.findAllById(any())).thenReturn(List.of(product, product));
        when(pricingRuleRepository.findApplicableForProducts(any(), any(), any(), any())).thenReturn(List.of());

        PricingComputeRequest request = new PricingComputeRequest(
                "store-1",
                List.of(new PricingItemRequest(productId, 1), new PricingItemRequest(productId, 2)),
                null,
                UUID.randomUUID());

        PricingBreakdownResponse breakdown = pricingService.compute(request);

        assertThat(breakdown.items()).hasSize(2);
        assertThat(breakdown.subtotalCents()).isEqualTo(15000L);
        assertThat(breakdown.totalCents()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("RateLimitService removes evicted limiter entries from RateLimiterRegistry")
    void rateLimitServiceEvictsRegistryOnCaffeineEviction() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(60))
                .timeoutDuration(Duration.ZERO)
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        registry.rateLimiter("productLimiter", config);

        RateLimitService rateLimitService = new RateLimitService(registry);
        rateLimitService.checkProduct("203.0.113.5");
        assertThat(registry.find("productLimiter:203.0.113.5")).isPresent();

        rateLimitService.cleanUpLocalLimiters();
        assertThat(registry.find("productLimiter:203.0.113.5")).isEmpty();
    }

    @Test
    @DisplayName("KafkaConfig configures CommonErrorHandler and handles byte[], String, and Map DLT payloads")
    void kafkaConfigSerializesDltPayloadTypes() {
        KafkaConfig kafkaConfig = new KafkaConfig();
        ReflectionTestUtils.setField(kafkaConfig, "bootstrapServers", "localhost:9092");

        DefaultKafkaProducerFactory<String, Object> pf =
                (DefaultKafkaProducerFactory<String, Object>) kafkaConfig.producerFactory();
        Serializer<Object> valueSerializer = pf.getValueSerializer();
        assertThat(valueSerializer).isNotNull();

        byte[] raw = "{bad-json".getBytes(StandardCharsets.UTF_8);
        assertThat(valueSerializer.serialize("catalog.events.DLT", raw)).isEqualTo(raw);
        assertThat(valueSerializer.serialize("catalog.events.DLT", "plain-dlt"))
                .isEqualTo("plain-dlt".getBytes(StandardCharsets.UTF_8));
        assertThat(valueSerializer.serialize("catalog.events.DLT", Map.of("eventType", "ProductUpdated")))
                .asString(StandardCharsets.UTF_8)
                .contains("ProductUpdated");
        assertThat(kafkaConfig.kafkaErrorHandler(new KafkaTemplate<>(pf))).isNotNull();
    }
}
