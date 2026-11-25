package com.instacommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.cart.client.PricingClient;
import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.controller.CartController;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.exception.ApiException;
import com.instacommerce.cart.exception.GlobalExceptionHandler;
import com.instacommerce.cart.exception.TraceIdProvider;
import com.instacommerce.cart.repository.CartItemRepository;
import com.instacommerce.cart.repository.CartRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CartServiceCheckoutAndValidationTest {

    @Test
    @DisplayName("validateCart detects stale item prices, updates cart prices, and reports priceChanged details")
    void validateCartDetectsPriceStalenessAndRefreshesPrices() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        CartService service = new CartService(cartStore, props, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        Cart staleCart = buildCart(cartId, userId, "store-blr-01",
                List.of(item(p1, "Milk", 5000L, 2), item(p2, "Bread", 3000L, 1)), 3L);
        Cart refreshedCart = buildCart(cartId, userId, "store-blr-01",
                List.of(item(p1, "Milk", 5500L, 2), item(p2, "Bread", 3000L, 1)), 4L);

        when(cartStore.validateCart(userId)).thenReturn(staleCart);
        when(pricingClient.getPricesBatch(anyCollection())).thenReturn(Map.of(
                p1, new PricingClient.PriceResponse(p1, 5500L, "INR"),
                p2, new PricingClient.PriceResponse(p2, 3000L, "INR")));
        when(cartStore.updateItemPrices(eq(userId), anyMap())).thenReturn(refreshedCart);

        CartResponse response = service.validateCart(userId);

        assertThat(response.valid()).isTrue();
        assertThat(response.unavailableProductIds()).isEmpty();
        assertThat(response.priceChanged()).isTrue();
        assertThat(response.priceChanges()).hasSize(1);
        assertThat(response.priceChanges().getFirst().productId()).isEqualTo(p1);
        assertThat(response.priceChanges().getFirst().previousUnitPriceCents()).isEqualTo(5000L);
        assertThat(response.priceChanges().getFirst().currentUnitPriceCents()).isEqualTo(5500L);
        assertThat(response.subtotalCents()).isEqualTo(14000L);
        assertThat(response.version()).isEqualTo(4L);
        verify(cartStore).updateItemPrices(userId, Map.of(p1, 5500L));
    }

    @Test
    @DisplayName("validateCart flags deactivated SKUs from batch pricing as unavailable instead of failing with 503")
    void validateCartFlagsUnavailableProductsFromBatch() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        CartService service = new CartService(cartStore, props, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID activeProduct = UUID.randomUUID();
        UUID deactivatedProduct = UUID.randomUUID();

        Cart cart = buildCart(cartId, userId, "store-blr-01",
                List.of(item(activeProduct, "Milk", 5000L, 1), item(deactivatedProduct, "Old Snack", 2000L, 1)), 1L);

        when(cartStore.validateCart(userId)).thenReturn(cart);
        when(pricingClient.getPricesBatch(anyCollection())).thenReturn(Map.of(
                activeProduct, new PricingClient.PriceResponse(activeProduct, 5000L, "INR")));

        CartResponse response = service.validateCart(userId);

        assertThat(response.valid()).isFalse();
        assertThat(response.unavailableProductIds()).containsExactly(deactivatedProduct);
    }

    @Test
    @DisplayName("validateCart flags single deactivated SKU when getPrice throws 404 PRODUCT_UNAVAILABLE")
    void validateCartFlagsSingleUnavailableProduct() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        CartService service = new CartService(cartStore, props, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID deactivatedProduct = UUID.randomUUID();

        Cart cart = buildCart(cartId, userId, "store-blr-01",
                List.of(item(deactivatedProduct, "Old Snack", 2000L, 1)), 1L);

        when(cartStore.validateCart(userId)).thenReturn(cart);
        when(pricingClient.getPrice(deactivatedProduct))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_UNAVAILABLE", "Unavailable"));

        CartResponse response = service.validateCart(userId);

        assertThat(response.valid()).isFalse();
        assertThat(response.unavailableProductIds()).containsExactly(deactivatedProduct);
    }

    @Test
    @DisplayName("validateCart enforces store serviceability and minimum order amount bounds")
    void validateCartEnforcesStoreServiceabilityAndMinOrder() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        props.setServiceableStores(List.of("store-blr-01", "store-blr-02"));
        props.setMinOrderCents(10000L);
        CartService service = new CartService(cartStore, props, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();

        Cart unserviceable = buildCart(cartId, userId, "store-del-99",
                List.of(item(p1, "Milk", 12000L, 1)), 1L);
        when(cartStore.validateCart(userId)).thenReturn(unserviceable);

        assertThatThrownBy(() -> service.validateCart(userId))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNSERVICEABLE_STORE");

        Cart smallCart = buildCart(cartId, userId, "store-blr-01",
                List.of(item(p1, "Milk", 4000L, 1)), 1L);
        when(cartStore.validateCart(userId)).thenReturn(smallCart);
        when(pricingClient.getPrice(p1)).thenReturn(new PricingClient.PriceResponse(p1, 4000L, "INR"));

        assertThatThrownBy(() -> service.validateCart(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 10000");
    }

    @Test
    @DisplayName("validateCart evicts carts cache before throwing IllegalStateException on min/max subtotal bounds")
    void validateCartEvictsCacheEvenWhenMinOrderValidationFails() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        props.setMinOrderCents(10_000L);

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(CartStore.class, () -> cartStore);
            ctx.registerBean(PricingClient.class, () -> pricingClient);
            ctx.registerBean(CartProperties.class, () -> props);
            ctx.register(CachingTestConfig.class);
            ctx.refresh();

            CartService proxiedService = ctx.getBean(CartService.class);
            CacheManager cacheManager = ctx.getBean(CacheManager.class);

            UUID userId = UUID.randomUUID();
            UUID cartId = UUID.randomUUID();
            UUID p1 = UUID.randomUUID();
            Cart initialCart = buildCart(cartId, userId, "store-blr-01",
                    List.of(item(p1, "Milk", 12_000L, 1)), 1L);
            Cart loweredCart = buildCart(cartId, userId, "store-blr-01",
                    List.of(item(p1, "Milk", 4_000L, 1)), 2L);

            when(cartStore.getCart(userId))
                    .thenReturn(Optional.of(initialCart))
                    .thenReturn(Optional.of(loweredCart));
            when(cartStore.validateCart(userId)).thenReturn(initialCart);
            when(pricingClient.getPrice(p1)).thenReturn(new PricingClient.PriceResponse(p1, 4_000L, "INR"));
            when(cartStore.updateItemPrices(userId, Map.of(p1, 4_000L))).thenReturn(loweredCart);

            CartResponse cached = proxiedService.getCart(userId);
            assertThat(cached.subtotalCents()).isEqualTo(12_000L);
            assertThat(cacheManager.getCache("carts").get(userId)).isNotNull();

            assertThatThrownBy(() -> proxiedService.validateCart(userId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 10000");

            assertThat(cacheManager.getCache("carts").get(userId)).isNull();
            assertThat(proxiedService.getCart(userId).subtotalCents()).isEqualTo(4_000L);
        }
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("carts");
        }

        @Bean
        CartService cartService(CartStore cartStore, CartProperties props, PricingClient pricingClient) {
            return new CartService(cartStore, props, pricingClient, null);
        }
    }

    @Test
    @DisplayName("addItem validates request bounds and JpaCartStore enforces store mismatch and limits atomically")
    void addItemRejectsStoreMismatchAndLimitViolations() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties props = new CartProperties();
        props.setServiceableStores(List.of("store-blr-01"));
        props.setMaxItemsPerCart(2);
        props.setMaxQuantityPerItem(5);
        CartService service = new CartService(cartStore, props, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();

        assertThatThrownBy(() -> service.addItem(
                userId, new AddItemRequest(UUID.randomUUID(), "Coffee", 1000L, 1, "store-blr-99")))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNSERVICEABLE_STORE");

        assertThatThrownBy(() -> service.addItem(
                userId, new AddItemRequest(p1, "Milk", 3000L, 6, "store-blr-01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed maximum of 5");

        CartRepository cartRepo = mock(CartRepository.class);
        CartItemRepository itemRepo = mock(CartItemRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        JpaCartStore store = new JpaCartStore(cartRepo, itemRepo, outbox, props);

        CartItem existingItem = item(p1, "Milk", 3000L, 1);
        Cart existingCart = buildCart(cartId, userId, "store-blr-01", List.of(existingItem), 1L);
        when(cartRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existingCart));
        when(itemRepo.findByCartIdAndProductId(cartId, p1)).thenReturn(Optional.of(existingItem));

        assertThatThrownBy(() -> store.addItem(
                userId, new AddItemRequest(UUID.randomUUID(), "Coffee", 1000L, 1, "store-blr-02")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> store.addItem(
                userId, new AddItemRequest(p1, "Milk", 3000L, 5, "store-blr-01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed maximum of 5");
    }

    @Test
    @DisplayName("JpaCartStore enforces atomic expectedVersion on updateQuantity and publishes CartUpdated outbox event")
    void jpaCartStoreEnforcesAtomicVersionAndEmitsUpdatedEvent() {
        CartRepository cartRepo = mock(CartRepository.class);
        CartItemRepository itemRepo = mock(CartItemRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        CartProperties props = new CartProperties();
        JpaCartStore store = new JpaCartStore(cartRepo, itemRepo, outbox, props);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CartItem cartItem = item(productId, "Oats", 2500L, 2);
        Cart cart = buildCart(cartId, userId, "store-blr-01", List.of(cartItem), 5L);

        when(cartRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(cart));
        when(itemRepo.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.of(cartItem));
        when(cartRepo.saveAndFlush(cart)).thenReturn(cart);

        assertThatThrownBy(() -> store.updateQuantity(userId, productId, 3, 4L))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PRECONDITION_FAILED");

        store.updateQuantity(userId, productId, 3, 5L);
        assertThat(cartItem.getQuantity()).isEqualTo(3);
        verify(outbox).publish(eq("Cart"), eq(cartId.toString()), eq("CartUpdated"), any());
    }

    @Test
    @DisplayName("JpaCartStore emits canonical Cart aggregateType and schema-aligned outbox event names/fields")
    @SuppressWarnings("unchecked")
    void jpaCartStoreEmitsCanonicalOutboxPayloadsWithSchemaFields() {
        CartRepository cartRepo = mock(CartRepository.class);
        CartItemRepository itemRepo = mock(CartItemRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        JpaCartStore store = new JpaCartStore(cartRepo, itemRepo, outbox, new CartProperties());

        UUID userId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Cart cart = buildCart(cartId, userId, "store-blr-01", new java.util.ArrayList<>(), 1L);

        when(cartRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(cart));
        when(cartRepo.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartRepo.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.empty());

        store.addItem(userId, new AddItemRequest(productId, "Milk", 3500L, 2, "store-blr-01"));
        ArgumentCaptor<Map<String, Object>> addedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("Cart"), eq(cartId.toString()), eq("CartItemAdded"), addedCaptor.capture());
        assertThat(addedCaptor.getValue()).containsKeys(
                "cartId", "userId", "productId", "productName", "unitPriceCents", "quantity", "addedAt");

        CartItem unnamedItem = item(productId, null, 3500L, 1);
        when(itemRepo.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.of(unnamedItem));
        store.removeItem(userId, productId);
        ArgumentCaptor<Map<String, Object>> removedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("Cart"), eq(cartId.toString()), eq("CartItemRemoved"), removedCaptor.capture());
        assertThat(removedCaptor.getValue()).containsKeys("cartId", "userId", "productId", "productName", "removedAt");

        store.clearCart(userId);
        ArgumentCaptor<Map<String, Object>> clearedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("Cart"), eq(cartId.toString()), eq("CartCleared"), clearedCaptor.capture());
        assertThat(clearedCaptor.getValue()).containsKeys("cartId", "userId", "itemCount", "clearedAt");

        Cart guestCart = buildCart(UUID.randomUUID(), guestId, "store-blr-01",
                List.of(item(productId, "Milk", 3500L, 1)), 0L);
        when(cartRepo.findByUserIdForUpdate(guestId)).thenReturn(Optional.of(guestCart));
        when(itemRepo.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.empty());
        store.mergeCart(userId, guestId);
        ArgumentCaptor<Map<String, Object>> mergedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("Cart"), eq(cartId.toString()), eq("CartMerged"), mergedCaptor.capture());
        assertThat(mergedCaptor.getValue())
                .containsEntry("targetUserId", userId.toString())
                .containsEntry("guestUserId", guestId.toString());
    }

    @Test
    @DisplayName("CartController rejects malformed non-numeric If-Match ETag with 400 INVALID_ETAG")
    void cartControllerRejectsMalformedIfMatchHeader() throws Exception {
        CartService cartService = mock(CartService.class);
        CartController controller = new CartController(cartService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new TraceIdProvider()))
                .build();

        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", productId)
                        .principal(new TestingAuthenticationToken(userId.toString(), null, "ROLE_USER"))
                        .header("If-Match", "\"not-a-number\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ETAG"));
    }

    private static Cart buildCart(UUID cartId, UUID userId, String storeId, List<CartItem> items, long version) {
        Cart cart = new Cart();
        cart.setId(cartId);
        cart.setUserId(userId);
        cart.setStoreId(storeId);
        cart.setExpiresAt(Instant.now().plusSeconds(3600));
        cart.setVersion(version);
        cart.setItems(items);
        return cart;
    }

    private static CartItem item(UUID productId, String name, long unitPriceCents, int quantity) {
        CartItem item = new CartItem();
        item.setProductId(productId);
        item.setProductName(name);
        item.setUnitPriceCents(unitPriceCents);
        item.setQuantity(quantity);
        return item;
    }
}
