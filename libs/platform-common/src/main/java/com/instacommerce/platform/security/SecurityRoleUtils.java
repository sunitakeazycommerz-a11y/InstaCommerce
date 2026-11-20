package com.instacommerce.platform.security;

import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Shared authorization authority checks for controller and service security boundaries.
 */
public final class SecurityRoleUtils {
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_INTERNAL_SERVICE = SharedInternalServiceAuthFilter.INTERNAL_SERVICE_ROLE;

    private SecurityRoleUtils() {
    }

    public static boolean hasRole(Authentication authentication, String... roles) {
        if (authentication == null || roles == null || roles.length == 0) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        for (GrantedAuthority authority : authorities) {
            String value = authority != null ? authority.getAuthority() : null;
            if (value == null) {
                continue;
            }
            for (String expected : roles) {
                if (expected != null && !expected.isBlank() && expected.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isPrivileged(Authentication authentication) {
        return hasRole(authentication, ROLE_ADMIN, ROLE_INTERNAL_SERVICE);
    }

    public static boolean isAdmin(Authentication authentication) {
        return hasRole(authentication, ROLE_ADMIN);
    }

    public static boolean isInternalService(Authentication authentication) {
        return hasRole(authentication, ROLE_INTERNAL_SERVICE);
    }
}
