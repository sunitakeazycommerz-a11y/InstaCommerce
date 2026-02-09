package com.instacommerce.identity.exception;

import org.springframework.http.HttpStatus;

public class UserInactiveException extends ApiException {
    public UserInactiveException() {
        super(HttpStatus.FORBIDDEN, "USER_INACTIVE", "User account is not active");
    }
}
