package com.instacommerce.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Shared authorization authority, role normalization, and JWT audience checks
 * for controller and service security boundaries.
 */
public final class SecurityRoleUtils {
    public static final String ROLE_PREFIX = "ROLE_";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_INTERNAL_SERVICE = SharedInternalServiceAuthFilter.INTERNAL_SERVICE_ROLE;

    private SecurityRoleUtils() {
    }

    public static String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.regionMatches(true, 0, ROLE_PREFIX, 0, ROLE_PREFIX.length())) {
            String suffix = trimmed.substring(ROLE_PREFIX.length()).trim();
            return suffix.isEmpty() ? null : ROLE_PREFIX + suffix;
        }
        return ROLE_PREFIX + trimmed;
    }

    public static List<GrantedAuthority> extractAuthorities(Object rolesClaim) {
        if (rolesClaim == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        if (rolesClaim instanceof Collection<?> collection) {
            for (Object entry : collection) {
                addNormalizedRole(normalized, Objects.toString(entry, null));
            }
        } else if (rolesClaim instanceof String rawRoles) {
            for (String part : rawRoles.split(",")) {
                addNormalizedRole(normalized, part);
            }
        } else {
            return List.of();
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<GrantedAuthority> authorities = new ArrayList<>(normalized.size());
        for (String role : normalized) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        return List.copyOf(authorities);
    }

    public static boolean hasAcceptedAudience(
            Collection<String> tokenAudiences,
            Collection<String> acceptedAudiences) {
        if (tokenAudiences == null || tokenAudiences.isEmpty()
                || acceptedAudiences == null || acceptedAudiences.isEmpty()) {
            return false;
        }
        Set<String> accepted = new LinkedHashSet<>();
        for (String candidate : acceptedAudiences) {
            if (candidate != null && !candidate.isBlank()) {
                accepted.add(candidate.trim());
            }
        }
        if (accepted.isEmpty()) {
            return false;
        }
        for (String aud : tokenAudiences) {
            if (aud != null && accepted.contains(aud.trim())) {
                return true;
            }
        }
        return false;
    }

    private static void addNormalizedRole(Set<String> out, String rawRole) {
        String normalized = normalizeRole(rawRole);
        if (normalized != null) {
            out.add(normalized);
        }
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
