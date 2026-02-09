package com.instacommerce.cart.repository;

import com.instacommerce.cart.domain.model.Cart;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    Optional<Cart> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM Cart c WHERE c.expiresAt < :now")
    int deleteExpiredCarts(@Param("now") Instant now);
}
