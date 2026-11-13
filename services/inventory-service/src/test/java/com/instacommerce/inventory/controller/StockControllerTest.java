package com.instacommerce.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.StockCheckRequest;
import com.instacommerce.inventory.dto.response.StockCheckResponse;
import com.instacommerce.inventory.security.ClientIpResolver;
import com.instacommerce.inventory.service.InventoryService;
import com.instacommerce.inventory.service.RateLimitService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("check uses trusted-proxy right-to-left IP resolution instead of spoofed leftmost X-Forwarded-For")
    void checkUsesRightmostUntrustedIpForRateLimiting() {
        StockController controller = new StockController(inventoryService, rateLimitService, new ClientIpResolver());
        StockCheckRequest request = new StockCheckRequest(
            UUID.randomUUID(),
            List.of(new InventoryItemRequest(UUID.randomUUID(), 2))
        );
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("10.0.0.2");
        httpRequest.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.25, 172.16.0.5");

        when(rateLimitService.tryAcquire("198.51.100.25")).thenReturn(true);
        when(inventoryService.checkAvailability(request)).thenReturn(new StockCheckResponse(List.of()));

        StockCheckResponse response = controller.check(request, httpRequest);

        assertThat(response.items()).isEmpty();
        verify(rateLimitService).tryAcquire("198.51.100.25");
        verify(inventoryService).checkAvailability(request);
    }

    @Test
    @DisplayName("check ignores X-Forwarded-For from untrusted peer and enforces 429 rate limit")
    void checkIgnoresXffFromUntrustedPeerAndReturns429() {
        StockController controller = new StockController(inventoryService, rateLimitService, new ClientIpResolver());
        StockCheckRequest request = new StockCheckRequest(
            UUID.randomUUID(),
            List.of(new InventoryItemRequest(UUID.randomUUID(), 1))
        );
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("203.0.113.9");
        httpRequest.addHeader("X-Forwarded-For", "127.0.0.1");

        when(rateLimitService.tryAcquire("203.0.113.9")).thenReturn(false);

        assertThatThrownBy(() -> controller.check(request, httpRequest))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(rateLimitService).tryAcquire("203.0.113.9");
        verifyNoInteractions(inventoryService);
    }
}
