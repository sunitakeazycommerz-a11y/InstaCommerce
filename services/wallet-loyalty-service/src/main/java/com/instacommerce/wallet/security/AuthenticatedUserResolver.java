package com.instacommerce.wallet.security;

import com.instacommerce.platform.security.SecurityRoleUtils;
import com.instacommerce.wallet.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthenticatedUserResolver {

    public static final String USER_ID_HEADER = "X-User-Id";

    private AuthenticatedUserResolver() {
    }

    public static UUID resolveUserId(Authentication auth, String userIdHeader) {
        Authentication resolvedAuth = auth != null
            ? auth
            : SecurityContextHolder.getContext().getAuthentication();
        if (SecurityRoleUtils.isPrivileged(resolvedAuth)) {
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                try {
                    return UUID.fromString(userIdHeader.trim());
                } catch (IllegalArgumentException ex) {
                    throw missingUserIdException();
                }
            }
            if (resolvedAuth != null && resolvedAuth.getName() != null && !resolvedAuth.getName().isBlank()) {
                try {
                    return UUID.fromString(resolvedAuth.getName().trim());
                } catch (IllegalArgumentException ex) {
                    throw missingUserIdException();
                }
            }
            throw missingUserIdException();
        }
        if (resolvedAuth == null || resolvedAuth.getName() == null || resolvedAuth.getName().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        try {
            return UUID.fromString(resolvedAuth.getName().trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_USER_ID",
                "Authenticated user subject is not a valid UUID");
        }
    }

    private static ApiException missingUserIdException() {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "MISSING_USER_ID",
            "X-User-Id header is required for internal service calls");
    }
}
