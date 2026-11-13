package com.instacommerce.inventory.security;

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
    @DisplayName("ignores spoofed X-Forwarded-For when remote peer is not a trusted proxy")
    void ignoresSpoofedXffFromUntrustedPeer() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("extracts rightmost untrusted hop across multiple trusted proxy headers")
    void resolvesRightmostUntrustedHop() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeaders("X-Forwarded-For")).thenReturn(Collections.enumeration(List.of(
            "1.2.3.4, 203.0.113.8",
            "172.16.0.2, ::1"
        )));

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.8");
    }

    @Test
    @DisplayName("falls back to remoteAddr when X-Forwarded-For contains malformed or hostname entries")
    void fallsBackOnMalformedHop() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeaders("X-Forwarded-For"))
            .thenReturn(Collections.enumeration(List.of("spoofed.host.local, 10.1.2.3")));

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
