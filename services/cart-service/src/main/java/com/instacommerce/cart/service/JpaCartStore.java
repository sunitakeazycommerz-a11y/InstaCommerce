package com.instacommerce.cart.service;

import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.exception.CartNotFoundException;
import com.instacommerce.cart.exception.CartItemNotFoundException;
import com.instacommerce.cart.repository.CartItemRepository;
import com.instacommerce.cart.repository.CartRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaCartStore implements CartStore {
    private static final Logger log = LoggerFactory.getLogger(JpaCartStore.class);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    public JpaCartStore(CartRepository cartRepository, CartItemRepository cartItemRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "carts", key = "#userId")
    public Optional<Cart> getCart(UUID userId) {
        return cartRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    @CacheEvict(value = "carts", key = "#userId")
    public Cart addItem(UUID userId, AddItemRequest request) {
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> {
            Cart newCart = new Cart();
            newCart.setUserId(userId);
            newCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            return cartRepository.save(newCart);
        });

        // Idempotent: if product already in cart, increase quantity
        Optional<CartItem> existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId());
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(item.getQuantity() + request.quantity());
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            cartItemRepository.save(item);
        } else {
            CartItem item = new CartItem();
            item.setProductId(request.productId());
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            item.setQuantity(request.quantity());
            cart.addItem(item);
            cartRepository.save(cart);
        }

        // Extend expiry on mutation
        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return cartRepository.save(cart);
    }

    @Override
    @Transactional
    @CacheEvict(value = "carts", key = "#userId")
    public Cart updateQuantity(UUID userId, UUID productId, int quantity) {
        Cart cart = cartRepository.findByUserId(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));

        CartItem item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
            .orElseThrow(() -> new CartItemNotFoundException(productId));

        item.setQuantity(quantity);
        cartItemRepository.save(item);

        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return cartRepository.save(cart);
    }

    @Override
    @Transactional
    @CacheEvict(value = "carts", key = "#userId")
    public Cart removeItem(UUID userId, UUID productId) {
        Cart cart = cartRepository.findByUserId(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));

        CartItem item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
            .orElseThrow(() -> new CartItemNotFoundException(productId));

        cart.removeItem(item);
        cartItemRepository.delete(item);

        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return cartRepository.save(cart);
    }

    @Override
    @Transactional
    @CacheEvict(value = "carts", key = "#userId")
    public void clearCart(UUID userId) {
        cartRepository.findByUserId(userId).ifPresent(cartRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "carts", key = "#userId")
    public Cart validateCart(UUID userId) {
        Cart cart = cartRepository.findByUserId(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));

        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }
        if (cart.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("Cart has expired");
        }
        return cart;
    }
}
