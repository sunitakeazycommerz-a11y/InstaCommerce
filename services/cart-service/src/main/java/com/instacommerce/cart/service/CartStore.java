package com.instacommerce.cart.service;

import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.dto.request.AddItemRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage abstraction for cart state. Implementations may back onto
 * PostgreSQL, Redis, or any other data store.
 */
public interface CartStore {

    Optional<Cart> getCart(UUID userId);

    Cart addItem(UUID userId, AddItemRequest item);

    Cart updateQuantity(UUID userId, UUID productId, int quantity);

    Cart removeItem(UUID userId, UUID productId);

    void clearCart(UUID userId);

    Cart validateCart(UUID userId);
}
