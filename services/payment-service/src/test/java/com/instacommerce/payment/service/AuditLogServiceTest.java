package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.AuditLog;
import com.instacommerce.payment.exception.TraceIdProvider;
import com.instacommerce.payment.repository.AuditLogRepository;
import com.instacommerce.payment.util.RequestContextUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService – client IP resolution")
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TraceIdProvider traceIdProvider;

    @Mock
    private AuditLogService selfProxy;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(
            auditLogRepository,
            traceIdProvider,
            new SimpleMeterRegistry(),
            selfProxy
        );
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private String captureLoggedIp(MockHttpServletRequest request) {
        if (request != null) {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        } else {
            RequestContextHolder.resetRequestAttributes();
        }
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        auditLogService.log(UUID.randomUUID(), "PAYMENT_CREATED", "Payment", "pay-1", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue().getIpAddress();
    }

    @Test
    void ignoresForwardedHeaderWhenRemoteAddrIsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.25");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");

        assertThat(captureLoggedIp(request)).isEqualTo("198.51.100.25");
    }

    @Test
    void walksRightToLeftThroughSingleTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.42");

        assertThat(captureLoggedIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void skipsMultipleTrustedProxiesIncludingGcpLoadBalancers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("130.211.2.15");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.77, 192.168.10.4, 35.191.4.2, 10.2.3.4");

        assertThat(captureLoggedIp(request)).isEqualTo("203.0.113.77");
    }

    @Test
    void ignoresSpoofedLeftmostInvalidEntryBehindRealClientHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "<script>alert(1)</script>, 198.51.100.42, 172.16.0.2");

        assertThat(captureLoggedIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void fallsBackToRemoteAddrWhenRightmostUntrustedHopIsMalformed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "198.51.100.42, not-an-ip, 172.16.0.2");

        assertThat(captureLoggedIp(request)).isEqualTo("10.0.1.5");
    }

    @Test
    void resolvesIpv6ClientAndStripsPorts() {
        MockHttpServletRequest ipv6Request = new MockHttpServletRequest();
        ipv6Request.setRemoteAddr("fd00::10");
        ipv6Request.addHeader("X-Forwarded-For", "2001:db8:dead::1, [2001:db8:cafe::2]:51234, ::1, fc00::5");

        assertThat(captureLoggedIp(ipv6Request)).isEqualTo("2001:db8:cafe::2");
    }

    @Test
    void resolvesClientBehindIpv4MappedIpv6TrustedProxy() {
        MockHttpServletRequest mappedRequest = new MockHttpServletRequest();
        mappedRequest.setRemoteAddr("::ffff:10.0.0.5");
        mappedRequest.addHeader("X-Forwarded-For", "198.51.100.12, ::ffff:172.16.1.4");

        assertThat(captureLoggedIp(mappedRequest)).isEqualTo("198.51.100.12");
    }

    @Test
    void handlesMultipleForwardedHeadersRightToLeft() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.88");
        request.addHeader("X-Forwarded-For", "10.0.0.2");

        assertThat(captureLoggedIp(request)).isEqualTo("198.51.100.88");
    }

    @Test
    void returnsNullIpWhenNoActiveHttpRequest() {
        assertThat(captureLoggedIp(null)).isNull();
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderMissingOrBlank() {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.setRemoteAddr("10.0.0.5");
        assertThat(RequestContextUtil.resolveIp(missing)).isEqualTo("10.0.0.5");

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("10.0.0.5");
        blank.addHeader("X-Forwarded-For", "   ");
        assertThat(RequestContextUtil.resolveIp(blank)).isEqualTo("10.0.0.5");
    }

    @Test
    void returnsLeftmostTrustedHopWhenEntireChainIsTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 172.16.0.2");

        assertThat(captureLoggedIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void supportsCustomTrustedCidrsAndRejectsInvalidCidrConfiguration() {
        List<String> customCidrs = List.of("203.0.113.0/26", "2001:db8:1::/48");

        MockHttpServletRequest trustedPeer = new MockHttpServletRequest();
        trustedPeer.setRemoteAddr("203.0.113.63");
        trustedPeer.addHeader("X-Forwarded-For", "198.51.100.19, 2001:db8:1:ffff::4");
        assertThat(RequestContextUtil.resolveIp(trustedPeer, customCidrs)).isEqualTo("198.51.100.19");

        MockHttpServletRequest outsideMask = new MockHttpServletRequest();
        outsideMask.setRemoteAddr("203.0.113.64");
        outsideMask.addHeader("X-Forwarded-For", "198.51.100.19");
        assertThat(RequestContextUtil.resolveIp(outsideMask, customCidrs)).isEqualTo("203.0.113.64");

        assertThatThrownBy(() -> RequestContextUtil.resolveIp(trustedPeer, List.of("10.0.0.0/33")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
