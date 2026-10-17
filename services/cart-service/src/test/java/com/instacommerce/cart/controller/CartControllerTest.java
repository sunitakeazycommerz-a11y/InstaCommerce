package com.instacommerce.cart.controller;

import com.instacommerce.cart.dto.response.CartItemResponse;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.service.CartService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    @Mock
    private CartService cartService;

    @InjectMocks
    private CartController cartController;

    private final UUID userId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void validateCartByUserIdReturnsValidResponseWithStoreId() {
        CartItemResponse item = new CartItemResponse(productId, "Milk", 500L, 1, 500L);
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(item), 500L, 1, Instant.now()
        );

        when(cartService.validateCart(userId)).thenReturn(cartResponse);

        ResponseEntity<CartResponse> response = cartController.validateCartByUserId(userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().storeId()).isEqualTo(CartResponse.DEFAULT_STORE_ID);
        assertThat(response.getBody().items()).hasSize(1);
    }

    @Test
    void clearCartByUserIdReturnsNoContent() {
        ResponseEntity<Void> response = cartController.clearCartByUserId(userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cartService).clearCart(userId);
    }

    @Test
    void getCartByUserIdReturnsCart() {
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(), 0L, 0, Instant.now()
        );

        when(cartService.getCart(userId)).thenReturn(cartResponse);

        ResponseEntity<CartResponse> response = cartController.getCartByUserId(userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(userId);
    }
}
