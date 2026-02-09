package com.instacommerce.cart.controller;

import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.request.UpdateQuantityRequest;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.service.CartService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(Authentication authentication,
                                                @Valid @RequestBody AddItemRequest request) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.addItem(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateQuantity(Authentication authentication,
                                                       @PathVariable UUID productId,
                                                       @Valid @RequestBody UpdateQuantityRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cartService.updateQuantity(userId, productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(Authentication authentication,
                                                   @PathVariable UUID productId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cartService.removeItem(userId, productId));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    public ResponseEntity<CartResponse> validateCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cartService.validateCart(userId));
    }

    private UUID extractUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
