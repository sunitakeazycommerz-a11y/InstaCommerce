package com.instacommerce.identity.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestContextUtilTest {

    @Test
    void ignoresForwardedHeaderWhenRemoteAddrIsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.25");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("198.51.100.25");
    }

    @Test
    void preventsSpoofingByWalkingRightToLeftThroughSingleTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.42");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void skipsMultipleTrustedProxiesAndStopsAtFirstUntrustedHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.77, 192.168.10.4, 172.20.1.8, 10.2.3.4");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("203.0.113.77");
    }

    @Test
    void ignoresSpoofedLeftmostInvalidEntryBehindRealClientHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "<script>alert(1)</script>, 198.51.100.42, 172.16.0.2");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void fallsBackToRemoteAddrWhenRightmostUntrustedHopIsMalformed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "198.51.100.42, not-an-ip, 172.16.0.2");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("10.0.1.5");
    }

    @Test
    void resolvesIpv6ClientBehindIpv6TrustedProxies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("fd00::10");
        request.addHeader("X-Forwarded-For", "2001:db8:dead::1, 2001:db8:cafe::2, ::1, fc00::5");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("2001:db8:cafe::2");
    }

    @Test
    void ignoresForwardedHeaderWhenIpv6RemoteAddrIsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::99");
        request.addHeader("X-Forwarded-For", "203.0.113.1");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("2001:db8::99");
    }

    @Test
    void stripsPortsAndBracketsFromForwardedHops() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "[2001:db8::42]:51234, 10.0.0.2:8080");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("2001:db8::42");
    }

    @Test
    void handlesMultipleForwardedHeadersRightToLeft() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.88");
        request.addHeader("X-Forwarded-For", "10.0.0.2");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("198.51.100.88");
    }

    @Test
    void returnsLeftmostTrustedHopWhenEntireChainIsTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 172.16.0.2");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderIsBlankOrMissing() {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.setRemoteAddr("10.0.0.5");
        assertThat(RequestContextUtil.resolveIp(missing)).isEqualTo("10.0.0.5");

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("10.0.0.5");
        blank.addHeader("X-Forwarded-For", "   ");
        assertThat(RequestContextUtil.resolveIp(blank)).isEqualTo("10.0.0.5");
    }

    @Test
    void handlesNullRequestSafely() {
        assertThat(RequestContextUtil.resolveIp(null)).isNull();
        assertThat(RequestContextUtil.resolveUserAgent(null)).isNull();
    }

    @Test
    void resolvesClientBehindIpv4MappedIpv6TrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::ffff:10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.12, ::ffff:172.16.1.4");

        assertThat(RequestContextUtil.resolveIp(request)).isEqualTo("198.51.100.12");
    }

    @Test
    void supportsCustomTrustedCidrBitmasksAndGcpLoadBalancerRanges() {
        List<String> customCidrs = List.of("203.0.113.0/26", "2001:db8:1::/48");

        MockHttpServletRequest trustedPeer = new MockHttpServletRequest();
        trustedPeer.setRemoteAddr("203.0.113.63");
        trustedPeer.addHeader("X-Forwarded-For", "198.51.100.19, 2001:db8:1:ffff::4");
        assertThat(RequestContextUtil.resolveIp(trustedPeer, customCidrs)).isEqualTo("198.51.100.19");

        MockHttpServletRequest outsideMask = new MockHttpServletRequest();
        outsideMask.setRemoteAddr("203.0.113.64");
        outsideMask.addHeader("X-Forwarded-For", "198.51.100.19");
        assertThat(RequestContextUtil.resolveIp(outsideMask, customCidrs)).isEqualTo("203.0.113.64");

        MockHttpServletRequest gcpLb = new MockHttpServletRequest();
        gcpLb.setRemoteAddr("130.211.2.15");
        gcpLb.addHeader("X-Forwarded-For", "198.51.100.55, 35.191.4.2");
        assertThat(RequestContextUtil.resolveIp(gcpLb)).isEqualTo("198.51.100.55");
    }

    @Test
    void rejectsInvalidCustomTrustedCidrConfiguration() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThatThrownBy(() -> RequestContextUtil.resolveIp(request, List.of("10.0.0.0/33")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
