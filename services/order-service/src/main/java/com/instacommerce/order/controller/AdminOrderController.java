package com.instacommerce.order.controller;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.request.OrderStatusUpdateRequest;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {
    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable UUID id,
                            @Valid @RequestBody CancelOrderRequest request,
                            @AuthenticationPrincipal String principal) {
        String changedBy = principal == null ? "admin" : "admin:" + principal;
        orderService.cancelOrder(id, request.reason(), changedBy, toUserId(principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.getOrder(id, null, true);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateStatus(@PathVariable UUID id,
                             @Valid @RequestBody OrderStatusUpdateRequest request,
                             @AuthenticationPrincipal String principal) {
        String changedBy = principal == null ? "admin" : "admin:" + principal;
        OrderStatus status = request.status();
        String note = request.note() == null ? "status update" : request.note();
        orderService.updateOrderStatus(id, status, changedBy, note, toUserId(principal));
    }

    private UUID toUserId(String principal) {
        return principal == null ? null : UUID.fromString(principal);
    }
}
