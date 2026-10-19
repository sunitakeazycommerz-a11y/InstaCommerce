package com.instacommerce.order.controller;

import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.request.OrderCreateRequest;
import com.instacommerce.order.dto.response.OrderCreationResult;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.service.OrderService;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
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
@RequestMapping({"/orders", "/api/orders"})
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreationResult createOrder(@Valid @RequestBody OrderCreateRequest request) {
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(UUID.fromString(request.userId()))
            .storeId(request.storeId())
            .items(request.items())
            .subtotalCents(request.subtotalCents())
            .discountCents(request.discountCents())
            .totalCents(request.totalCents())
            .currency(request.currency())
            .couponCode(request.couponCode())
            .reservationId(request.reservationId() != null && !request.reservationId().isBlank() ? UUID.fromString(request.reservationId()) : null)
            .paymentId(request.paymentId() != null && !request.paymentId().isBlank() ? UUID.fromString(request.paymentId()) : null)
            .idempotencyKey(request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID().toString())
            .deliveryAddress(request.effectiveDeliveryAddress())
            .quoteId(request.quoteId() != null && !request.quoteId().isBlank() ? UUID.fromString(request.quoteId()) : null)
            .quoteToken(request.quoteToken())
            .build();

        String orderId = orderService.createOrder(command);
        return new OrderCreationResult(orderId, 15);
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
                            @AuthenticationPrincipal String principal,
                            Authentication authentication) {
        if (isAdmin(authentication) || isInternalService(authentication)) {
            String changedBy = principal != null ? principal : "internal-service";
            orderService.cancelOrder(id, request.reason(), changedBy, null);
            return;
        }
        UUID userId = UUID.fromString(principal);
        orderService.cancelOrderByUser(id, userId, request.reason());
    }

    @PostMapping("/{id}/amend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateAmendment(@PathVariable UUID id,
                                  @AuthenticationPrincipal String principal,
                                  Authentication authentication) {
        orderService.validateOrderAmendmentWindow(id);
    }

    private UUID toUserId(String principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private boolean isInternalService(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_INTERNAL_SERVICE".equals(authority.getAuthority()));
    }
}
