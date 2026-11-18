package com.instacommerce.cart.service;

import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.exception.CartItemNotFoundException;
import com.instacommerce.cart.exception.CartNotFoundException;
import com.instacommerce.cart.repository.CartItemRepository;
import com.instacommerce.cart.repository.CartRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaCartStore implements CartStore {
    private static final String AGGREGATE_TYPE = "CART";
    private static final String EVENT_ITEM_ADDED = "ITEM_ADDED";
    private static final String EVENT_ITEM_REMOVED = "ITEM_REMOVED";
    private static final String EVENT_CART_CLEARED = "CART_CLEARED";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OutboxService outboxService;
    private final CartProperties cartProperties;

    @Autowired
    public JpaCartStore(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        OutboxService outboxService,
                        CartProperties cartProperties) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.outboxService = outboxService;
        this.cartProperties = cartProperties != null ? cartProperties : new CartProperties();
    }

    public JpaCartStore(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        OutboxService outboxService) {
        this(cartRepository, cartItemRepository, outboxService, new CartProperties());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Cart> getCart(UUID userId) {
        return cartRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public Cart addItem(UUID userId, AddItemRequest request) {
        Cart cart = cartRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
            Cart newCart = new Cart();
            newCart.setUserId(userId);
            newCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            return cartRepository.saveAndFlush(newCart);
        });

        Optional<CartItem> existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId());
        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            int newQuantity = item.getQuantity() + request.quantity();
            if (newQuantity > cartProperties.getMaxQuantityPerItem()) {
                throw new IllegalArgumentException(
                    "Total quantity for this item would exceed maximum of "
                        + cartProperties.getMaxQuantityPerItem());
            }
            item.setQuantity(newQuantity);
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            cartItemRepository.save(item);
        } else {
            if (cart.getItems().size() >= cartProperties.getMaxItemsPerCart()) {
                throw new IllegalArgumentException(
                    "Cart cannot exceed " + cartProperties.getMaxItemsPerCart() + " distinct items");
            }
            if (request.quantity() > cartProperties.getMaxQuantityPerItem()) {
                throw new IllegalArgumentException(
                    "Total quantity for this item would exceed maximum of "
                        + cartProperties.getMaxQuantityPerItem());
            }
            item = new CartItem();
            item.setProductId(request.productId());
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            item.setQuantity(request.quantity());
            cart.addItem(item);
        }

        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedCart = cartRepository.saveAndFlush(cart);
        outboxService.publish(AGGREGATE_TYPE, savedCart.getId().toString(), EVENT_ITEM_ADDED,
            itemPayload(userId, savedCart, item));
        return savedCart;
    }

    @Override
    @Transactional
    public Cart updateQuantity(UUID userId, UUID productId, int quantity) {
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
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
    public Cart removeItem(UUID userId, UUID productId) {
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));

        CartItem item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
            .orElseThrow(() -> new CartItemNotFoundException(productId));

        cart.removeItem(item);
        cartItemRepository.delete(item);

        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedCart = cartRepository.save(cart);
        outboxService.publish(AGGREGATE_TYPE, savedCart.getId().toString(), EVENT_ITEM_REMOVED,
            itemPayload(userId, savedCart, item));
        return savedCart;
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        cartRepository.findByUserIdForUpdate(userId).ifPresent(cart -> {
            outboxService.publish(AGGREGATE_TYPE, cart.getId().toString(), EVENT_CART_CLEARED,
                Map.of("userId", userId, "cartId", cart.getId(), "itemCount", cart.getItems().size()));
            cartRepository.delete(cart);
        });
    }

    @Override
    @Transactional(readOnly = true)
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

    @Override
    @Transactional
    public Cart mergeCart(UUID targetUserId, UUID sourceGuestUserId) {
        if (targetUserId.equals(sourceGuestUserId)) {
            return cartRepository.findByUserIdForUpdate(targetUserId).orElseGet(() -> {
                Cart newCart = new Cart();
                newCart.setUserId(targetUserId);
                newCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
                return cartRepository.saveAndFlush(newCart);
            });
        }

        Cart targetCart = cartRepository.findByUserIdForUpdate(targetUserId).orElseGet(() -> {
            Cart newCart = new Cart();
            newCart.setUserId(targetUserId);
            newCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            return cartRepository.saveAndFlush(newCart);
        });

        Optional<Cart> guestCartOpt = cartRepository.findByUserIdForUpdate(sourceGuestUserId);
        if (guestCartOpt.isEmpty()) {
            return targetCart;
        }

        Cart guestCart = guestCartOpt.get();
        if (guestCart.getExpiresAt() != null && guestCart.getExpiresAt().isBefore(Instant.now())) {
            cartRepository.delete(guestCart);
            return targetCart;
        }
        if (guestCart.getItems().isEmpty()) {
            cartRepository.delete(guestCart);
            return targetCart;
        }

        int maxQtyPerItem = cartProperties.getMaxQuantityPerItem();
        int maxItemsPerCart = cartProperties.getMaxItemsPerCart();

        for (CartItem guestItem : guestCart.getItems()) {
            Optional<CartItem> existingTargetItem = cartItemRepository.findByCartIdAndProductId(
                targetCart.getId(), guestItem.getProductId());
            if (existingTargetItem.isPresent()) {
                CartItem targetItem = existingTargetItem.get();
                int combinedQty = Math.min(targetItem.getQuantity() + guestItem.getQuantity(), maxQtyPerItem);
                targetItem.setQuantity(combinedQty);
                cartItemRepository.save(targetItem);
            } else if (targetCart.getItems().size() < maxItemsPerCart) {
                CartItem newItem = new CartItem();
                newItem.setProductId(guestItem.getProductId());
                newItem.setProductName(guestItem.getProductName());
                newItem.setUnitPriceCents(guestItem.getUnitPriceCents());
                newItem.setQuantity(Math.min(guestItem.getQuantity(), maxQtyPerItem));
                targetCart.addItem(newItem);
            }
        }

        targetCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedTargetCart = cartRepository.saveAndFlush(targetCart);

        cartRepository.delete(guestCart);

        outboxService.publish(AGGREGATE_TYPE, savedTargetCart.getId().toString(), "CART_MERGED",
            Map.of("targetUserId", targetUserId.toString(), "guestUserId", sourceGuestUserId.toString()));

        return savedTargetCart;
    }

    private Map<String, Object> itemPayload(UUID userId, Cart cart, CartItem item) {
        return Map.of(
            "userId", userId,
            "cartId", cart.getId(),
            "productId", item.getProductId(),
            "quantity", item.getQuantity(),
            "unitPriceCents", item.getUnitPriceCents(),
            "productName", item.getProductName()
        );
    }
}
