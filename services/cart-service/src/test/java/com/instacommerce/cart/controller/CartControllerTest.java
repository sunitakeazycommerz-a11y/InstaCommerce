package com.instacommerce.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.cart.config.JacksonConfig;
import com.instacommerce.cart.config.SecurityConfig;
import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.request.UpdateQuantityRequest;
import com.instacommerce.cart.dto.response.CartItemResponse;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.exception.GlobalExceptionHandler;
import com.instacommerce.cart.exception.TraceIdProvider;
import com.instacommerce.cart.security.InternalServiceAuthFilter;
import com.instacommerce.cart.security.JwtAuthenticationFilter;
import com.instacommerce.cart.security.JwtService;
import com.instacommerce.cart.security.RestAccessDeniedHandler;
import com.instacommerce.cart.security.RestAuthenticationEntryPoint;
import com.instacommerce.cart.service.CartService;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CartController.class)
@Import({
    SecurityConfig.class,
    JwtAuthenticationFilter.class,
    InternalServiceAuthFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    GlobalExceptionHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class
})
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private JwtService jwtService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void validateCartByUserIdReturnsValidResponseForOwner() throws Exception {
        CartItemResponse item = new CartItemResponse(productId, "Milk", 500L, 1, 500L);
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(item), 500L, 1, Instant.now()
        );
        when(cartService.validateCart(userId)).thenReturn(cartResponse);

        mockMvc.perform(get("/cart/{userId}/validate", userId)
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.storeId").value(CartResponse.DEFAULT_STORE_ID))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void clearCartByUserIdReturnsNoContentForOwner() throws Exception {
        mockMvc.perform(delete("/cart/{userId}", userId)
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isNoContent());

        verify(cartService).clearCart(userId);
    }

    @Test
    void getCartByUserIdReturnsCartWithETagForOwner() throws Exception {
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(), 0L, 0, Instant.now(), 2L
        );
        when(cartService.getCart(userId)).thenReturn(cartResponse);

        mockMvc.perform(get("/cart/{userId}", userId)
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void crossUserGetCartReturnsForbiddenAccessDenied() throws Exception {
        mockMvc.perform(get("/cart/{userId}", userId)
                .with(user(otherUserId.toString()).roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(cartService, never()).getCart(any());
    }

    @Test
    void crossUserClearCartReturnsForbiddenAccessDenied() throws Exception {
        mockMvc.perform(delete("/cart/{userId}", userId)
                .with(user(otherUserId.toString()).roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(cartService, never()).clearCart(any());
    }

    @Test
    void crossUserValidateCartEndpointsReturnForbiddenAccessDenied() throws Exception {
        mockMvc.perform(get("/cart/{userId}/validate", userId)
                .with(user(otherUserId.toString()).roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/cart/validate/{userId}", userId)
                .with(user(otherUserId.toString()).roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/cart/{userId}/validate", userId)
                .with(user(otherUserId.toString()).roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(cartService, never()).validateCart(any());
    }

    @Test
    void nonUuidPrincipalWithoutPrivilegedRoleReturnsForbiddenAccessDenied() throws Exception {
        mockMvc.perform(get("/cart/{userId}", userId)
                .with(user("not-a-uuid").roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(cartService, never()).getCart(any());
    }

    @Test
    void adminRoleCanAccessAnyUserCartEndpoints() throws Exception {
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(), 0L, 0, Instant.now(), 4L
        );
        when(cartService.getCart(userId)).thenReturn(cartResponse);
        when(cartService.validateCart(userId)).thenReturn(cartResponse);

        mockMvc.perform(get("/cart/{userId}", userId)
                .with(user("admin-1").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        mockMvc.perform(get("/api/v1/cart/{userId}/validate", userId)
                .with(user("admin-1").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        mockMvc.perform(delete("/api/v1/cart/{userId}", userId)
                .with(user("admin-1").roles("ADMIN")))
            .andExpect(status().isNoContent());

        verify(cartService).clearCart(userId);
    }

    @Test
    void internalServiceTokenCanAccessAnyUserCartAcrossAllPrefixes() throws Exception {
        CartResponse cartResponse = new CartResponse(
            cartId, userId, List.of(), 500L, 1, Instant.now(), 5L
        );
        when(cartService.getCart(userId)).thenReturn(cartResponse);
        when(cartService.validateCart(userId)).thenReturn(cartResponse);

        mockMvc.perform(get("/api/v1/cart/{userId}", userId)
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "test-internal-service-token"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"5\""))
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        mockMvc.perform(get("/api/carts/{userId}/validate", userId)
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "test-internal-service-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        mockMvc.perform(delete("/api/v1/cart/{userId}", userId)
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "test-internal-service-token"))
            .andExpect(status().isNoContent());

        verify(cartService).clearCart(userId);
    }

    @Test
    void unauthenticatedRequestsToApiV1CartAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/cart/{userId}", userId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        when(jwtService.validateAccessToken("bad-token")).thenThrow(new JwtException("invalid"));
        mockMvc.perform(get("/api/v1/cart/{userId}", userId)
                .header("Authorization", "Bearer bad-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void apiV1CartRoutesSupportOwnerCartOperations() throws Exception {
        CartResponse emptyCart = new CartResponse(
            cartId, userId, List.of(), 0L, 0, Instant.now(), 1L
        );
        CartResponse itemAddedCart = new CartResponse(
            cartId,
            userId,
            List.of(new CartItemResponse(productId, "Milk", 500L, 2, 1000L)),
            1000L,
            2,
            Instant.now(),
            2L
        );
        when(cartService.getCart(userId)).thenReturn(emptyCart);
        when(cartService.addItem(eq(userId), any(AddItemRequest.class))).thenReturn(itemAddedCart);
        when(cartService.validateCart(userId)).thenReturn(itemAddedCart);

        mockMvc.perform(get("/api/v1/cart")
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        AddItemRequest addRequest = new AddItemRequest(productId, "Milk", 500L, 2);
        mockMvc.perform(post("/api/v1/cart/items")
                .with(user(userId.toString()).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addRequest)))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.subtotalCents").value(1000L));

        mockMvc.perform(post("/api/v1/cart/validate")
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void mergeCartReturnsMergedCartWithETag() throws Exception {
        UUID guestUserId = UUID.randomUUID();
        CartResponse mergedResponse = new CartResponse(
            cartId, userId, List.of(), 1000L, 2, Instant.now(), 3L
        );
        when(cartService.mergeCart(userId, guestUserId)).thenReturn(mergedResponse);

        mockMvc.perform(post("/api/v1/cart/merge")
                .param("guestUserId", guestUserId.toString())
                .with(user(userId.toString()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"3\""));

        verify(cartService).mergeCart(userId, guestUserId);
    }

    @Test
    void updateQuantityFailsOnStaleIfMatchHeader() throws Exception {
        CartResponse currentResponse = new CartResponse(
            cartId, userId, List.of(), 500L, 1, Instant.now(), 2L
        );
        when(cartService.getCart(userId)).thenReturn(currentResponse);

        UpdateQuantityRequest request = new UpdateQuantityRequest(3);
        mockMvc.perform(patch("/api/v1/cart/items/{productId}", productId)
                .with(user(userId.toString()).roles("USER"))
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }
}
