package com.instacommerce.identity.exception;

import org.springframework.http.HttpStatus;

public class TokenRevokedException extends ApiException {
    public TokenRevokedException() {
        super(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token has been revoked");
    }
}
