package com.instacommerce.order.controller;

import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Page<OrderSummaryResponse> listOrders(@AuthenticationPrincipal String principal, Pageable pageable) {
        return orderService.listOrders(UUID.fromString(principal), pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id,
                                  @AuthenticationPrincipal String principal,
                                  Authentication authentication) {
        return orderService.getOrder(id, toUserId(principal), isAdmin(authentication));
    }

    @GetMapping("/{id}/status")
    public OrderStatusResponse getOrderStatus(@PathVariable UUID id,
                                              @AuthenticationPrincipal String principal,
                                              Authentication authentication) {
        return orderService.getOrderStatus(id, toUserId(principal), isAdmin(authentication));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable UUID id,
                            @Valid @RequestBody CancelOrderRequest request,
                            @AuthenticationPrincipal String principal) {
        UUID userId = UUID.fromString(principal);
        orderService.cancelOrderByUser(id, userId, request.reason());
    }

    private UUID toUserId(String principal) {
        return principal == null ? null : UUID.fromString(principal);
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
