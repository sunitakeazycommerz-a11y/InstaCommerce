package com.instacommerce.order.exception;

import org.springframework.http.HttpStatus;

public class OrderAmendmentForbiddenException extends ApiException {
    public OrderAmendmentForbiddenException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_ALREADY_PACKING", message);
    }
}
