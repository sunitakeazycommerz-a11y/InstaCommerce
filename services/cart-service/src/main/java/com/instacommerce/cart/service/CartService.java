package com.instacommerce.cart.service;

import com.instacommerce.cart.client.PricingClient;
import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.response.CartItemResponse;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.exception.CartNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CartService {
    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartStore cartStore;
    private final CartProperties cartProperties;
    private final PricingClient pricingClient;

    public CartService(CartStore cartStore, CartProperties cartProperties, PricingClient pricingClient) {
        this.cartStore = cartStore;
        this.cartProperties = cartProperties;
        this.pricingClient = pricingClient;
    }

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
    public CartResponse addItem(UUID userId, AddItemRequest request) {
        validateBusinessRules(userId, request);

        // Server-side price validation: always use pricing-service as source of truth
        PricingClient.PriceResponse priceResponse = pricingClient.getPrice(request.productId());
        long serverPrice = priceResponse.unitPriceCents();

        if (request.unitPriceCents() != null && request.unitPriceCents() != serverPrice) {
            log.warn("Price mismatch for user={}, product={}: client sent {} cents, server price is {} cents. Possible fraud attempt.",
                    userId, request.productId(), request.unitPriceCents(), serverPrice);
        }

        // Override client-supplied price with server-authoritative price
        AddItemRequest validatedRequest = new AddItemRequest(
                request.productId(), request.productName(), serverPrice, request.quantity());

        Cart cart = cartStore.addItem(userId, validatedRequest);
        log.info("Item added to cart for user={}, product={}, serverPrice={}", userId, request.productId(), serverPrice);
        return toResponse(cart);
    }

    public CartResponse updateQuantity(UUID userId, UUID productId, int quantity) {
        if (quantity > cartProperties.getMaxQuantityPerItem()) {
            throw new IllegalArgumentException(
                "Quantity exceeds maximum of " + cartProperties.getMaxQuantityPerItem());
        }
        Cart cart = cartStore.updateQuantity(userId, productId, quantity);
        log.info("Quantity updated for user={}, product={}, qty={}", userId, productId, quantity);
        return toResponse(cart);
    }

    public CartResponse removeItem(UUID userId, UUID productId) {
        Cart cart = cartStore.removeItem(userId, productId);
        log.info("Item removed from cart for user={}, product={}", userId, productId);
        return toResponse(cart);
    }

    public void clearCart(UUID userId) {
        cartStore.clearCart(userId);
        log.info("Cart cleared for user={}", userId);
    }

    public CartResponse validateCart(UUID userId) {
        Cart cart = cartStore.validateCart(userId);
        log.info("Cart validated for checkout, user={}", userId);
        return toResponse(cart);
    }

    private void validateBusinessRules(UUID userId, AddItemRequest request) {
        cartStore.getCart(userId).ifPresent(cart -> {
            // Check max items per cart
            boolean productExists = cart.getItems().stream()
                .anyMatch(item -> item.getProductId().equals(request.productId()));
            if (!productExists && cart.getItems().size() >= cartProperties.getMaxItemsPerCart()) {
                throw new IllegalArgumentException(
                    "Cart cannot exceed " + cartProperties.getMaxItemsPerCart() + " distinct items");
            }

            // Check max quantity per item
            cart.getItems().stream()
                .filter(item -> item.getProductId().equals(request.productId()))
                .findFirst()
                .ifPresent(existing -> {
                    int newQty = existing.getQuantity() + request.quantity();
                    if (newQty > cartProperties.getMaxQuantityPerItem()) {
                        throw new IllegalArgumentException(
                            "Total quantity for this item would exceed maximum of "
                                + cartProperties.getMaxQuantityPerItem());
                    }
                });
        });
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
            .map(this::toItemResponse)
            .toList();
        long subtotal = cart.getItems().stream()
            .mapToLong(item -> item.getUnitPriceCents() * item.getQuantity())
            .sum();
        int itemCount = cart.getItems().stream()
            .mapToInt(CartItem::getQuantity)
            .sum();
        return new CartResponse(cart.getId(), cart.getUserId(), items, subtotal, itemCount, cart.getExpiresAt());
    }

    private CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
            item.getProductId(),
            item.getProductName(),
            item.getUnitPriceCents(),
            item.getQuantity(),
            item.getUnitPriceCents() * item.getQuantity()
        );
    }

    private CartResponse emptyCartResponse(UUID userId) {
        return new CartResponse(null, userId, Collections.emptyList(), 0L, 0, null);
    }
}
