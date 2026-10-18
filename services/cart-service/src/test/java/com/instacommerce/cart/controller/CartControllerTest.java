package com.instacommerce.cart.controller;

import com.instacommerce.cart.dto.request.UpdateQuantityRequest;
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
import org.springframework.security.core.Authentication;

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
            cartId, userId, List.of(), 0L, 0, Instant.now(), 2L
        );

        when(cartService.getCart(userId)).thenReturn(cartResponse);

        ResponseEntity<CartResponse> response = cartController.getCartByUserId(userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(userId);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"2\"");
    }

    @Test
    void mergeCartReturnsMergedCartWithETag() {
        UUID guestUserId = UUID.randomUUID();
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());

        CartResponse mergedResponse = new CartResponse(
            cartId, userId, List.of(), 1000L, 2, Instant.now(), 3L
        );
        when(cartService.mergeCart(userId, guestUserId)).thenReturn(mergedResponse);

        ResponseEntity<CartResponse> response = cartController.mergeCart(auth, guestUserId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
        verify(cartService).mergeCart(userId, guestUserId);
    }

    @Test
    void updateQuantityFailsOnStaleIfMatchHeader() {
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());

        CartResponse currentResponse = new CartResponse(
            cartId, userId, List.of(), 500L, 1, Instant.now(), 2L
        );
        when(cartService.getCart(userId)).thenReturn(currentResponse);

        UpdateQuantityRequest request = new UpdateQuantityRequest(3);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            cartController.updateQuantity(auth, productId, request, "\"1\"")
        ).isInstanceOf(com.instacommerce.cart.exception.ApiException.class)
         .hasMessageContaining("Cart version mismatch");
    }
}
