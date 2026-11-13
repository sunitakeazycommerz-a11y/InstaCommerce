package com.instacommerce.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("Catalog ClientIpResolver Unit Tests")
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    @DisplayName("Returns null for null request and remoteAddr when X-Forwarded-For is absent")
    void handlesNullAndMissingForwardedHeader() {
        assertThat(resolver.resolve(null)).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }

    @Test
    @DisplayName("Ignores X-Forwarded-For completely when direct peer is untrusted")
    void ignoresForwardedHeaderFromUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "127.0.0.1, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("Traverses multiple X-Forwarded-For headers right-to-left and stops at first untrusted hop")
    void resolvesMultipleHeadersRightToLeft() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.99");
        request.addHeader("X-Forwarded-For", "172.16.4.10, 10.20.30.40");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("Rejects malformed spoofed hop without proceeding leftward")
    void stopsAtMalformedHopWithoutFallingBackLeftward() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, evil.attacker.example, 10.0.0.9");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("Supports bracketed IPv6 with port and falls back to leftmost trusted hop when all hops are proxies")
    void supportsIpv6AndAllTrustedHops() {
        MockHttpServletRequest ipv6Request = new MockHttpServletRequest();
        ipv6Request.setRemoteAddr("::1");
        ipv6Request.addHeader("X-Forwarded-For", "1.2.3.4, [2001:db8::1]:443, 10.0.0.8");

        assertThat(resolver.resolve(ipv6Request)).isEqualTo("2001:db8:0:0:0:0:0:1");

        MockHttpServletRequest allTrustedRequest = new MockHttpServletRequest();
        allTrustedRequest.setRemoteAddr("10.0.0.5");
        allTrustedRequest.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.5");

        assertThat(resolver.resolve(allTrustedRequest)).isEqualTo("10.0.0.1");
    }
}
