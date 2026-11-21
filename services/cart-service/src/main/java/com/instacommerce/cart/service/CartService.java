package com.instacommerce.cart.service;

import com.instacommerce.cart.client.PricingClient;
import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.response.CartItemResponse;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.exception.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class CartService {
    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private static final int MAX_ADD_RETRIES = 3;

    private final CartStore cartStore;
    private final CartProperties cartProperties;
    private final PricingClient pricingClient;
    private final CartSyncPublisher cartSyncPublisher;

    @Autowired
    public CartService(CartStore cartStore, CartProperties cartProperties, PricingClient pricingClient,
                       ObjectProvider<CartSyncPublisher> cartSyncPublisherProvider) {
        this.cartStore = cartStore;
        this.cartProperties = cartProperties;
        this.pricingClient = pricingClient;
        this.cartSyncPublisher = cartSyncPublisherProvider != null ? cartSyncPublisherProvider.getIfAvailable() : null;
    }

    @Cacheable(value = "carts", key = "#userId", unless = "#result == null || #result.cartId() == null")
    public CartResponse getCart(UUID userId) {
        return cartStore.getCart(userId)
            .map(this::toResponse)
            .orElse(emptyCartResponse(userId));
    }

    /**
     * Adds an item to the user's cart. The unit price is always fetched from the
     * pricing-service to prevent client-side price manipulation. If the pricing-service
     * is unavailable, the request is rejected (fail-closed).
     */
    @CacheEvict(value = "carts", key = "#userId")
    public CartResponse addItem(UUID userId, AddItemRequest request) {
        validateStoreServiceabilityIfPresent(request.storeId());
        validateRequestQuantity(request);

        PricingClient.PriceResponse priceResponse = pricingClient.getPrice(request.productId());
        long serverPrice = priceResponse.unitPriceCents();

        if (request.unitPriceCents() != null && request.unitPriceCents() != serverPrice) {
            log.warn("Price mismatch for user={}, product={}: client sent {} cents, server price is {} cents. Possible fraud attempt.",
                    userId, request.productId(), request.unitPriceCents(), serverPrice);
        }

        AddItemRequest validatedRequest = new AddItemRequest(
                request.productId(), request.productName(), serverPrice, request.quantity(), request.storeId());

        Cart cart = executeAddWithRetry(userId, validatedRequest);
        log.info("Item added to cart for user={}, product={}, serverPrice={}", userId, request.productId(), serverPrice);
        CartResponse response = toResponse(cart);
        notifyCartUpdated(userId, response);
        return response;
    }

    private Cart executeAddWithRetry(UUID userId, AddItemRequest validatedRequest) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ADD_RETRIES; attempt++) {
            try {
                return cartStore.addItem(userId, validatedRequest);
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
                lastException = ex;
                log.debug("Concurrent addItem conflict on attempt {} for user={}, product={}",
                        attempt, userId, validatedRequest.productId());
            }
        }
        throw lastException;
    }

    @CacheEvict(value = "carts", key = "#userId")
    public CartResponse updateQuantity(UUID userId, UUID productId, int quantity) {
        return updateQuantity(userId, productId, quantity, null);
    }

    @CacheEvict(value = "carts", key = "#userId")
    public CartResponse updateQuantity(UUID userId, UUID productId, int quantity, Long expectedVersion) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (quantity > cartProperties.getMaxQuantityPerItem()) {
            throw new IllegalArgumentException(
                "Quantity exceeds maximum of " + cartProperties.getMaxQuantityPerItem());
        }
        Cart cart = cartStore.updateQuantity(userId, productId, quantity, expectedVersion);
        log.info("Quantity updated for user={}, product={}, qty={}", userId, productId, quantity);
        CartResponse response = toResponse(cart);
        notifyCartUpdated(userId, response);
        return response;
    }

    @CacheEvict(value = "carts", key = "#userId")
    public CartResponse removeItem(UUID userId, UUID productId) {
        Cart cart = cartStore.removeItem(userId, productId);
        log.info("Item removed from cart for user={}, product={}", userId, productId);
        CartResponse response = toResponse(cart);
        notifyCartUpdated(userId, response);
        return response;
    }

    @CacheEvict(value = "carts", key = "#userId")
    public void clearCart(UUID userId) {
        cartStore.clearCart(userId);
        log.info("Cart cleared for user={}", userId);
        notifyCartUpdated(userId, emptyCartResponse(userId));
    }

    @CacheEvict(value = "carts", key = "#userId", beforeInvocation = true)
    public CartResponse validateCart(UUID userId) {
        Cart cart = cartStore.validateCart(userId);
        validateStoreServiceabilityForCheckout(cart.getStoreId());

        List<CartResponse.PriceChangeDetail> priceChanges = List.of();
        List<UUID> unavailableProductIds = List.of();
        if (pricingClient != null && !cart.getItems().isEmpty()) {
            PriceLookupResult lookup = fetchLatestUnitPrices(cart.getItems());
            unavailableProductIds = lookup.unavailableProductIds();
            Map<UUID, Long> livePrices = lookup.unitPrices();
            Map<UUID, Long> updatedPrices = new LinkedHashMap<>();
            List<CartResponse.PriceChangeDetail> detectedChanges = new ArrayList<>();

            for (CartItem item : cart.getItems()) {
                Long latestPrice = livePrices.get(item.getProductId());
                if (latestPrice != null && latestPrice >= 0 && latestPrice != item.getUnitPriceCents()) {
                    detectedChanges.add(new CartResponse.PriceChangeDetail(
                            item.getProductId(),
                            item.getProductName(),
                            item.getUnitPriceCents(),
                            latestPrice));
                    updatedPrices.put(item.getProductId(), latestPrice);
                }
            }
            if (!updatedPrices.isEmpty()) {
                cart = cartStore.updateItemPrices(userId, updatedPrices);
                priceChanges = Collections.unmodifiableList(detectedChanges);
                log.info("Detected {} price change(s) during checkout preview for user={}", priceChanges.size(), userId);
            }
        }

        if (unavailableProductIds.isEmpty()) {
            long subtotal = computeSubtotal(cart);
            if (cartProperties.getMinOrderCents() > 0 && subtotal < cartProperties.getMinOrderCents()) {
                throw new IllegalStateException(
                    "Cart subtotal must be at least " + cartProperties.getMinOrderCents() + " cents");
            }
            if (cartProperties.getMaxCartValueCents() > 0 && subtotal > cartProperties.getMaxCartValueCents()) {
                throw new IllegalStateException(
                    "Cart value exceeds maximum allowed amount of " + cartProperties.getMaxCartValueCents() + " cents");
            }
        }

        log.info("Cart validated for checkout, user={}, priceChanged={}, unavailableCount={}",
                userId, !priceChanges.isEmpty(), unavailableProductIds.size());
        return toResponse(cart, !priceChanges.isEmpty(), priceChanges, unavailableProductIds);
    }

    @Caching(evict = {
        @CacheEvict(value = "carts", key = "#targetUserId"),
        @CacheEvict(value = "carts", key = "#sourceGuestUserId", condition = "#sourceGuestUserId != null")
    })
    public CartResponse mergeCart(UUID targetUserId, UUID sourceGuestUserId) {
        if (sourceGuestUserId == null || targetUserId.equals(sourceGuestUserId)) {
            return getCart(targetUserId);
        }
        Cart merged = cartStore.mergeCart(targetUserId, sourceGuestUserId);
        log.info("Merged guest cart {} into user cart {}", sourceGuestUserId, targetUserId);
        CartResponse response = toResponse(merged);
        notifyCartUpdated(targetUserId, response);
        return response;
    }

    private void notifyCartUpdated(UUID userId, CartResponse response) {
        if (cartSyncPublisher != null) {
            cartSyncPublisher.publishCartUpdated(userId, response);
        }
    }

    private record PriceLookupResult(Map<UUID, Long> unitPrices, List<UUID> unavailableProductIds) {
    }

    private PriceLookupResult fetchLatestUnitPrices(List<CartItem> items) {
        List<UUID> productIds = items.stream().map(CartItem::getProductId).distinct().toList();
        Map<UUID, Long> unitPrices = new LinkedHashMap<>();
        List<UUID> unavailableProductIds = new ArrayList<>();
        if (productIds.size() > 1) {
            Map<UUID, PricingClient.PriceResponse> batch = pricingClient.getPricesBatch(productIds);
            if (batch != null) {
                batch.forEach((id, resp) -> {
                    if (resp != null) {
                        unitPrices.put(id, resp.unitPriceCents());
                    }
                });
            }
            for (UUID productId : productIds) {
                if (!unitPrices.containsKey(productId)) {
                    unavailableProductIds.add(productId);
                }
            }
            return new PriceLookupResult(unitPrices, List.copyOf(unavailableProductIds));
        }
        for (UUID productId : productIds) {
            try {
                PricingClient.PriceResponse price = pricingClient.getPrice(productId);
                if (price != null) {
                    unitPrices.put(productId, price.unitPriceCents());
                } else {
                    unavailableProductIds.add(productId);
                }
            } catch (ApiException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    unavailableProductIds.add(productId);
                } else {
                    throw ex;
                }
            }
        }
        return new PriceLookupResult(unitPrices, List.copyOf(unavailableProductIds));
    }

    private void validateStoreServiceabilityIfPresent(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return;
        }
        List<String> serviceable = cartProperties.getServiceableStores();
        if (serviceable != null && !serviceable.isEmpty()) {
            String normalized = storeId.trim();
            boolean supported = serviceable.stream().anyMatch(s -> s.equalsIgnoreCase(normalized));
            if (!supported) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSERVICEABLE_STORE",
                    "Store or zone is currently not serviceable: " + normalized);
            }
        }
    }

    private void validateStoreServiceabilityForCheckout(String storeId) {
        List<String> serviceable = cartProperties.getServiceableStores();
        if (serviceable == null || serviceable.isEmpty()) {
            return;
        }
        if (storeId == null || storeId.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSERVICEABLE_STORE",
                "A serviceable store must be selected before checkout");
        }
        validateStoreServiceabilityIfPresent(storeId);
    }

    private void validateRequestQuantity(AddItemRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (request.quantity() > cartProperties.getMaxQuantityPerItem()) {
            throw new IllegalArgumentException(
                "Total quantity for this item would exceed maximum of " + cartProperties.getMaxQuantityPerItem());
        }
    }

    private long computeSubtotal(Cart cart) {
        long subtotal = 0L;
        for (CartItem item : cart.getItems()) {
            long lineTotal = Math.multiplyExact(item.getUnitPriceCents(), (long) item.getQuantity());
            subtotal = Math.addExact(subtotal, lineTotal);
        }
        return subtotal;
    }

    private CartResponse toResponse(Cart cart) {
        return toResponse(cart, false, List.of(), List.of());
    }

    private CartResponse toResponse(
            Cart cart,
            boolean priceChanged,
            List<CartResponse.PriceChangeDetail> priceChanges,
            List<UUID> unavailableProductIds) {
        List<CartItemResponse> items = cart.getItems().stream()
            .map(this::toItemResponse)
            .toList();
        long subtotal = computeSubtotal(cart);
        int itemCount = cart.getItems().stream()
            .mapToInt(CartItem::getQuantity)
            .sum();
        List<UUID> unavailable = unavailableProductIds != null ? List.copyOf(unavailableProductIds) : List.of();
        return new CartResponse(
            cart.getId(),
            cart.getUserId(),
            items,
            subtotal,
            itemCount,
            cart.getExpiresAt(),
            !items.isEmpty() && unavailable.isEmpty(),
            cart.getStoreId(),
            cart.getVersion(),
            priceChanged,
            priceChanges,
            unavailable);
    }

    private CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
            item.getProductId(),
            item.getProductName(),
            item.getUnitPriceCents(),
            item.getQuantity(),
            Math.multiplyExact(item.getUnitPriceCents(), (long) item.getQuantity())
        );
    }

    private CartResponse emptyCartResponse(UUID userId) {
        return new CartResponse(null, userId, Collections.emptyList(), 0L, 0, null, 0L);
    }
}
