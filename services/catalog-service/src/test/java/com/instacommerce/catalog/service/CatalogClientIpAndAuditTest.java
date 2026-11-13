package com.instacommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.instacommerce.catalog.controller.ProductController;
import com.instacommerce.catalog.controller.SearchController;
import com.instacommerce.catalog.domain.model.AuditLog;
import com.instacommerce.catalog.exception.TraceIdProvider;
import com.instacommerce.catalog.repository.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("Catalog Client IP Resolution Tests")
class CatalogClientIpAndAuditTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("AuditLogService ignores spoofed X-Forwarded-For when remoteAddr is untrusted")
    void auditLogIgnoresSpoofedXForwardedForFromUntrustedRemoteAddr() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        TraceIdProvider traceIdProvider = mock(TraceIdProvider.class);
        AuditLogService service = new AuditLogService(repository, traceIdProvider);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.log(UUID.randomUUID(), "UPDATE_PRODUCT", "PRODUCT", "p-1", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("AuditLogService resolves rightmost untrusted IP across trusted proxies")
    void auditLogResolvesRightmostUntrustedIpThroughTrustedProxies() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        TraceIdProvider traceIdProvider = mock(TraceIdProvider.class);
        AuditLogService service = new AuditLogService(repository, traceIdProvider);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.45, 10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.log(UUID.randomUUID(), "CREATE_PRODUCT", "PRODUCT", "p-2", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.45");
    }

    @Test
    @DisplayName("ProductController uses trusted-proxy client IP resolution for rate limiting")
    void productControllerUsesTrustedProxyIpResolution() {
        ProductService productService = mock(ProductService.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        ProductController controller = new ProductController(productService, rateLimitService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        UUID productId = UUID.randomUUID();
        controller.getProduct(productId, request);

        verify(rateLimitService).checkProduct("198.51.100.20");
    }

    @Test
    @DisplayName("SearchController resolves rightmost untrusted client IP through trusted proxies for rate limiting")
    void searchControllerUsesTrustedProxyIpResolution() {
        SearchService searchService = mock(SearchService.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        SearchController controller = new SearchController(searchService, rateLimitService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.88, 127.0.0.1");

        controller.search("milk", null, null, null, null, PageRequest.of(0, 20), request);

        verify(rateLimitService).checkSearch("198.51.100.88");
    }
}
