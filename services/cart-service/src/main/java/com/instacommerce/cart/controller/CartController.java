package com.instacommerce.cart.controller;

import com.instacommerce.cart.dto.request.AddItemRequest;
import com.instacommerce.cart.dto.request.UpdateQuantityRequest;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.cart.exception.ApiException;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/cart", "/api/carts"})
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.getCart(userId);
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<CartResponse> getCartByUserId(@PathVariable UUID userId) {
        CartResponse response = cartService.getCart(userId);
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(Authentication authentication,
                                                @Valid @RequestBody AddItemRequest request) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.addItem(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @PatchMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateQuantity(Authentication authentication,
                                                       @PathVariable UUID productId,
                                                       @Valid @RequestBody UpdateQuantityRequest request,
                                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        UUID userId = extractUserId(authentication);
        if (ifMatch != null && !ifMatch.isBlank()) {
            CartResponse current = cartService.getCart(userId);
            String rawVersion = ifMatch.replace("\"", "").trim();
            try {
                long expectedVersion = Long.parseLong(rawVersion);
                if (current.version() != null && current.version() != expectedVersion) {
                    throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                        "Cart version mismatch; client version is stale.");
                }
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric ETag format
            }
        }
        CartResponse response = cartService.updateQuantity(userId, productId, request.quantity());
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @PostMapping("/merge")
    public ResponseEntity<CartResponse> mergeCart(Authentication authentication,
                                                  @RequestParam UUID guestUserId) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.mergeCart(userId, guestUserId);
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @PostMapping("/merge/{guestUserId}")
    public ResponseEntity<CartResponse> mergeCartByPath(Authentication authentication,
                                                        @PathVariable UUID guestUserId) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.mergeCart(userId, guestUserId);
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(Authentication authentication,
                                                    @PathVariable UUID productId) {
        UUID userId = extractUserId(authentication);
        CartResponse response = cartService.removeItem(userId, productId);
        return ResponseEntity.ok()
            .eTag("\"" + response.version() + "\"")
            .body(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> clearCartByUserId(@PathVariable UUID userId) {
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    public ResponseEntity<CartResponse> validateCart(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cartService.validateCart(userId));
    }

    @GetMapping({"/{userId}/validate", "/validate/{userId}"})
    public ResponseEntity<CartResponse> validateCartByUserId(@PathVariable UUID userId) {
        return ResponseEntity.ok(cartService.validateCart(userId));
    }

    private UUID extractUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
