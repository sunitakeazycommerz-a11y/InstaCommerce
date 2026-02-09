package com.instacommerce.cart.repository;

import com.instacommerce.cart.domain.model.CartItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {
    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);
}
