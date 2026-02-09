package com.instacommerce.identity.exception;

import org.springframework.http.HttpStatus;

public class TokenInvalidException extends ApiException {
    public TokenInvalidException() {
        super(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "Token is invalid");
    }
}
