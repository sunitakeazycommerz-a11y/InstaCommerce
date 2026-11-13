package com.instacommerce.notification.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    @DisplayName("direct untrusted remote client ignores spoofed X-Forwarded-For")
    void ignoresXffFromUntrustedRemote() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("right-to-left traversal skips multiple trusted IPv4/IPv6 hops across repeated headers")
    void extractsFirstUntrustedHopFromRepeatedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeaders("X-Forwarded-For")).thenReturn(Collections.enumeration(List.of(
            "192.0.2.55, 198.51.100.44",
            "172.16.1.10, ::1"
        )));

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.44");
    }

    @Test
    @DisplayName("malformed IPv4 or hostname hop in X-Forwarded-For falls back to remoteAddr")
    void rejectsMalformedOrHostnameHop() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeaders("X-Forwarded-For"))
            .thenReturn(Collections.enumeration(List.of("evil.example.com, 10.1.2.3")));

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("respects custom configured trusted proxy CIDR")
    void supportsCustomTrustedProxyCidr() {
        ClientIpResolver custom = new ClientIpResolver(List.of("203.0.113.0/24"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeaders("X-Forwarded-For"))
            .thenReturn(Collections.enumeration(List.of("198.51.100.9, 203.0.113.42")));

        assertThat(custom.resolve(request)).isEqualTo("198.51.100.9");
    }
}
