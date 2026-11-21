package com.instacommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.cart.client.PricingClient;
import com.instacommerce.cart.config.CartProperties;
import com.instacommerce.cart.domain.model.Cart;
import com.instacommerce.cart.domain.model.CartItem;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.response.CartResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class CartServiceConcurrentTest {

    @Test
    void addItemRetriesOnTransientUniqueConstraintConflictWithoutDuplicatePricingCall() {
        CartStore cartStore = mock(CartStore.class);
        PricingClient pricingClient = mock(PricingClient.class);
        CartProperties properties = new CartProperties();
        CartService cartService = new CartService(cartStore, properties, pricingClient, null);

        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(pricingClient.getPrice(productId))
                .thenReturn(new PricingClient.PriceResponse(productId, 1200L, "INR"));

        Cart persisted = new Cart();
        persisted.setId(cartId);
        persisted.setUserId(userId);
        persisted.setExpiresAt(Instant.now().plusSeconds(3600));
        persisted.setVersion(1L);
        CartItem item = new CartItem();
        item.setProductId(productId);
        item.setProductName("Butter");
        item.setUnitPriceCents(1200L);
        item.setQuantity(1);
        persisted.addItem(item);

        when(cartStore.addItem(eq(userId), any(AddItemRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"))
                .thenReturn(persisted);

        CartResponse response = cartService.addItem(userId, new AddItemRequest(productId, "Butter", 1200L, 1));

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.subtotalCents()).isEqualTo(1200L);
        verify(pricingClient, times(1)).getPrice(productId);
        verify(cartStore, times(2)).addItem(eq(userId), any(AddItemRequest.class));
    }
}
