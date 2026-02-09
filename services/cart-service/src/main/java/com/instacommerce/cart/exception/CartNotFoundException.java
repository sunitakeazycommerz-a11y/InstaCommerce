package com.instacommerce.cart.exception;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public class CartNotFoundException extends ApiException {
    public CartNotFoundException(UUID userId) {
        super(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", "Cart not found for user " + userId);
    }
}
