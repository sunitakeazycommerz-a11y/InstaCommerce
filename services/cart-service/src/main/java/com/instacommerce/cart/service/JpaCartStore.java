package com.instacommerce.cart.service;

import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.exception.ApiException;
import com.instacommerce.cart.exception.CartItemNotFoundException;
import com.instacommerce.cart.exception.CartNotFoundException;
import com.instacommerce.cart.repository.CartItemRepository;
import com.instacommerce.cart.repository.CartRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaCartStore implements CartStore {
    private static final String AGGREGATE_TYPE = "Cart";
    private static final String EVENT_ITEM_ADDED = "CartItemAdded";
    private static final String EVENT_CART_UPDATED = "CartUpdated";
    private static final String EVENT_ITEM_REMOVED = "CartItemRemoved";
    private static final String EVENT_CART_CLEARED = "CartCleared";
    private static final String EVENT_CART_MERGED = "CartMerged";
    private static final String EVENT_CART_PRICE_REFRESHED = "CartPriceRefreshed";

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
        if (request.quantity() == null || request.quantity() < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        Cart cart = findOrCreateCartForUpdate(userId);
        applyOrVerifyStoreId(cart, request.storeId());

        Optional<CartItem> existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId());
        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            int newQuantity = Math.addExact(item.getQuantity(), request.quantity());
            validateQuantityBounds(newQuantity);
            item.setQuantity(newQuantity);
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            cartItemRepository.save(item);
        } else {
            if (cart.getItems().size() >= cartProperties.getMaxItemsPerCart()) {
                throw new IllegalArgumentException(
                    "Cart cannot exceed " + cartProperties.getMaxItemsPerCart() + " distinct items");
            }
            validateQuantityBounds(request.quantity());
            item = new CartItem();
            item.setProductId(request.productId());
            item.setProductName(request.productName());
            item.setUnitPriceCents(request.unitPriceCents());
            item.setQuantity(request.quantity());
            cart.addItem(item);
        }

        validateCartTotalLimit(cart);
        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedCart = cartRepository.saveAndFlush(cart);
        outboxService.publish(AGGREGATE_TYPE, savedCart.getId().toString(), EVENT_ITEM_ADDED,
            itemPayload(userId, savedCart, item, "addedAt"));
        return savedCart;
    }

    @Override
    @Transactional
    public Cart updateQuantity(UUID userId, UUID productId, int quantity) {
        return updateQuantity(userId, productId, quantity, null);
    }

    @Override
    @Transactional
    public Cart updateQuantity(UUID userId, UUID productId, int quantity, Long expectedVersion) {
        validateQuantityBounds(quantity);
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));

        if (expectedVersion != null && cart.getVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                "Cart version mismatch; client version is stale.");
        }

        CartItem item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
            .orElseThrow(() -> new CartItemNotFoundException(productId));

        item.setQuantity(quantity);
        cartItemRepository.save(item);

        validateCartTotalLimit(cart);
        cart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedCart = cartRepository.saveAndFlush(cart);
        outboxService.publish(AGGREGATE_TYPE, savedCart.getId().toString(), EVENT_CART_UPDATED,
            itemPayload(userId, savedCart, item, "updatedAt"));
        return savedCart;
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
            itemPayload(userId, savedCart, item, "removedAt"));
        return savedCart;
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        cartRepository.findByUserIdForUpdate(userId).ifPresent(cart -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("cartId", cart.getId());
            payload.put("itemCount", cart.getItems().size());
            payload.put("clearedAt", Instant.now().toString());
            outboxService.publish(AGGREGATE_TYPE, cart.getId().toString(), EVENT_CART_CLEARED, payload);
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
    public Cart updateItemPrices(UUID userId, Map<UUID, Long> latestUnitPrices) {
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CartNotFoundException(userId));
        if (latestUnitPrices == null || latestUnitPrices.isEmpty()) {
            return cart;
        }
        boolean updated = false;
        for (CartItem item : cart.getItems()) {
            Long newPrice = latestUnitPrices.get(item.getProductId());
            if (newPrice != null && newPrice >= 0 && item.getUnitPriceCents() != newPrice) {
                item.setUnitPriceCents(newPrice);
                cartItemRepository.save(item);
                updated = true;
            }
        }
        if (!updated) {
            return cart;
        }
        cart.setUpdatedAt(Instant.now());
        Cart savedCart = cartRepository.saveAndFlush(cart);
        outboxService.publish(AGGREGATE_TYPE, savedCart.getId().toString(), EVENT_CART_PRICE_REFRESHED,
            Map.of("userId", userId.toString(), "cartId", savedCart.getId().toString(), "updatedCount", latestUnitPrices.size()));
        return savedCart;
    }

    @Override
    @Transactional
    public Cart mergeCart(UUID targetUserId, UUID sourceGuestUserId) {
        Cart targetCart = findOrCreateCartForUpdate(targetUserId);
        if (targetUserId.equals(sourceGuestUserId)) {
            return targetCart;
        }

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

        applyOrVerifyStoreId(targetCart, guestCart.getStoreId());

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

        validateCartTotalLimit(targetCart);
        targetCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        Cart savedTargetCart = cartRepository.saveAndFlush(targetCart);

        cartRepository.delete(guestCart);

        outboxService.publish(AGGREGATE_TYPE, savedTargetCart.getId().toString(), EVENT_CART_MERGED,
            Map.of("targetUserId", targetUserId.toString(), "guestUserId", sourceGuestUserId.toString()));

        return savedTargetCart;
    }

    private Cart findOrCreateCartForUpdate(UUID userId) {
        return cartRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
            Cart newCart = new Cart();
            newCart.setUserId(userId);
            newCart.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            return cartRepository.saveAndFlush(newCart);
        });
    }

    private void applyOrVerifyStoreId(Cart cart, String requestedStoreId) {
        if (requestedStoreId == null || requestedStoreId.isBlank()) {
            return;
        }
        String normalized = requestedStoreId.trim();
        if (cart.getStoreId() != null && !cart.getStoreId().isBlank() && !cart.getItems().isEmpty()) {
            if (!cart.getStoreId().equalsIgnoreCase(normalized)) {
                throw new ApiException(HttpStatus.CONFLICT, "STORE_MISMATCH",
                    "Cart already contains items for store " + cart.getStoreId()
                        + "; clear cart before adding items from store " + normalized);
            }
        } else {
            cart.setStoreId(normalized);
        }
    }

    private void validateQuantityBounds(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (quantity > cartProperties.getMaxQuantityPerItem()) {
            throw new IllegalArgumentException(
                "Total quantity for this item would exceed maximum of "
                    + cartProperties.getMaxQuantityPerItem());
        }
    }

    private void validateCartTotalLimit(Cart cart) {
        long subtotal = 0L;
        for (CartItem item : cart.getItems()) {
            long lineTotal = Math.multiplyExact(item.getUnitPriceCents(), (long) item.getQuantity());
            subtotal = Math.addExact(subtotal, lineTotal);
        }
        if (cartProperties.getMaxCartValueCents() > 0 && subtotal > cartProperties.getMaxCartValueCents()) {
            throw new IllegalArgumentException(
                "Cart value exceeds maximum allowed amount of " + cartProperties.getMaxCartValueCents() + " cents");
        }
    }

    private Map<String, Object> itemPayload(UUID userId, Cart cart, CartItem item, String timestampField) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("cartId", cart.getId());
        payload.put("productId", item.getProductId());
        payload.put("quantity", item.getQuantity());
        payload.put("unitPriceCents", item.getUnitPriceCents());
        payload.put("productName", item.getProductName());
        payload.put(timestampField, Instant.now().toString());
        return payload;
    }
}
