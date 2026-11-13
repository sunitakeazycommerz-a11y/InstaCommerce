package com.instacommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.inventory.domain.model.AuditLog;
import com.instacommerce.inventory.exception.TraceIdProvider;
import com.instacommerce.inventory.repository.AuditLogRepository;
import com.instacommerce.inventory.security.ClientIpResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TraceIdProvider traceIdProvider;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("log ignores spoofed leftmost X-Forwarded-For hop when request comes from trusted proxy")
    void ignoresSpoofedLeftmostXffHop() {
        AuditLogService service = new AuditLogService(auditLogRepository, traceIdProvider, new ClientIpResolver());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.77, 192.168.1.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(traceIdProvider.resolveTraceId(request)).thenReturn("trace-1");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(UUID.randomUUID(), "STOCK_ADJUSTED", "StockItem", "item-1", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.77");
    }

    @Test
    @DisplayName("log ignores X-Forwarded-For when remote peer is untrusted")
    void ignoresXffWhenRemoteUntrusted() {
        AuditLogService service = new AuditLogService(auditLogRepository, traceIdProvider, new ClientIpResolver());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.88");
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(traceIdProvider.resolveTraceId(request)).thenReturn("trace-2");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(UUID.randomUUID(), "STOCK_ADJUSTED", "StockItem", "item-2", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("198.51.100.88");
    }
}
