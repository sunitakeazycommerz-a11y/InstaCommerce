package com.instacommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("SecurityRoleUtils tests")
class SecurityRoleUtilsTest {

    @Test
    void identifiesAdminInternalServiceAndPrivilegedRoles() {
        Authentication admin = new UsernamePasswordAuthenticationToken(
                "admin-1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        Authentication internal = new UsernamePasswordAuthenticationToken(
                "order-service", null, SharedInternalServiceAuthFilter.authoritiesFor("order-service"));
        Authentication customer = new UsernamePasswordAuthenticationToken(
                "user-1", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(SecurityRoleUtils.isPrivileged(admin)).isTrue();
        assertThat(SecurityRoleUtils.isAdmin(admin)).isTrue();
        assertThat(SecurityRoleUtils.isInternalService(admin)).isFalse();

        assertThat(SecurityRoleUtils.isPrivileged(internal)).isTrue();
        assertThat(SecurityRoleUtils.isAdmin(internal)).isFalse();
        assertThat(SecurityRoleUtils.isInternalService(internal)).isTrue();
        assertThat(SecurityRoleUtils.hasRole(internal, "ROLE_SERVICE_ORDER_SERVICE")).isTrue();

        assertThat(SecurityRoleUtils.isPrivileged(customer)).isFalse();
        assertThat(SecurityRoleUtils.hasRole(customer, "ROLE_USER", "ROLE_ADMIN")).isTrue();
        assertThat(SecurityRoleUtils.hasRole(customer, "ROLE_OPS")).isFalse();
    }

    @Test
    void failsClosedForNullMissingOrEmptyAuthorities() {
        Authentication nullAuthorities = mock(Authentication.class);
        when(nullAuthorities.getAuthorities()).thenReturn(null);

        Authentication emptyAuth = new UsernamePasswordAuthenticationToken("anon", null, List.of());

        assertThat(SecurityRoleUtils.isPrivileged(null)).isFalse();
        assertThat(SecurityRoleUtils.isPrivileged(nullAuthorities)).isFalse();
        assertThat(SecurityRoleUtils.isPrivileged(emptyAuth)).isFalse();
        assertThat(SecurityRoleUtils.hasRole(emptyAuth)).isFalse();
        assertThat(SecurityRoleUtils.hasRole(emptyAuth, (String[]) null)).isFalse();
    }

    @Test
    @DisplayName("Normalizes roles without double ROLE_ prefixing across list and string claims")
    void normalizesRolesAndExtractsAuthorities() {
        assertThat(SecurityRoleUtils.normalizeRole("ADMIN")).isEqualTo("ROLE_ADMIN");
        assertThat(SecurityRoleUtils.normalizeRole("ROLE_ADMIN")).isEqualTo("ROLE_ADMIN");
        assertThat(SecurityRoleUtils.normalizeRole("  role_SUPPORT  ")).isEqualTo("ROLE_SUPPORT");
        assertThat(SecurityRoleUtils.normalizeRole("ROLE_")).isNull();
        assertThat(SecurityRoleUtils.normalizeRole("   ")).isNull();

        assertThat(SecurityRoleUtils.extractAuthorities(List.of("ROLE_ADMIN", "USER", "ROLE_USER", "  ")))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_USER");

        assertThat(SecurityRoleUtils.extractAuthorities("ROLE_ADMIN, PICKER"))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_PICKER");

        assertThat(SecurityRoleUtils.extractAuthorities(42)).isEmpty();
    }

    @Test
    @DisplayName("Validates accepted JWT audiences fail-closed")
    void checksAcceptedAudiences() {
        assertThat(SecurityRoleUtils.hasAcceptedAudience(
                List.of("instacommerce-admin"), List.of("instacommerce-admin", "instacommerce-api")))
                .isTrue();
        assertThat(SecurityRoleUtils.hasAcceptedAudience(
                List.of("other-aud"), List.of("instacommerce-admin")))
                .isFalse();
        assertThat(SecurityRoleUtils.hasAcceptedAudience(List.of(), List.of("instacommerce-admin"))).isFalse();
        assertThat(SecurityRoleUtils.hasAcceptedAudience(null, List.of("instacommerce-admin"))).isFalse();
        assertThat(SecurityRoleUtils.hasAcceptedAudience(List.of("instacommerce-admin"), List.of())).isFalse();
    }
}
