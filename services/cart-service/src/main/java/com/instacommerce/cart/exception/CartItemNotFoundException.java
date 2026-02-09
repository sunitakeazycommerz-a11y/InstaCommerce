package com.instacommerce.cart.exception;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public class CartItemNotFoundException extends ApiException {
    public CartItemNotFoundException(UUID productId) {
        super(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "Cart item not found for product " + productId);
    }
}
