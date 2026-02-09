package com.instacommerce.identity.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyExistsException extends ApiException {
    public UserAlreadyExistsException() {
        super(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "A user with this email already exists");
    }
}
