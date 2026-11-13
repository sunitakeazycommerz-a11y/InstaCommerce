package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.notification.domain.model.AuditLog;
import com.instacommerce.notification.exception.TraceIdProvider;
import com.instacommerce.notification.repository.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TraceIdProvider traceIdProvider;

    @InjectMocks
    private AuditLogService auditLogService;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("ignores spoofed leftmost X-Forwarded-For hop when coming via trusted proxies")
    void ignoresSpoofedLeftmostXffHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.42, 10.0.2.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(traceIdProvider.resolveTraceId(request)).thenReturn("trace-1");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.log(UUID.randomUUID(), "DEVICE_REGISTERED", "DeviceToken", "tok-1", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.42");
    }

    @Test
    @DisplayName("ignores X-Forwarded-For completely when immediate peer is not a trusted proxy")
    void ignoresXffWhenRemoteAddrIsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.77");
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(traceIdProvider.resolveTraceId(request)).thenReturn("trace-2");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.log(UUID.randomUUID(), "DEVICE_REGISTERED", "DeviceToken", "tok-2", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("198.51.100.77");
    }
}
