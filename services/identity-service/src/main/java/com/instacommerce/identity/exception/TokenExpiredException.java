package com.instacommerce.identity.exception;

import org.springframework.http.HttpStatus;

public class TokenExpiredException extends ApiException {
    public TokenExpiredException() {
        super(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Token has expired");
    }
}
