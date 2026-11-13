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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/orders", "/api/orders", "/api/v1/orders"})
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreationResult createOrder(@Valid @RequestBody OrderCreateRequest request,
                                           Authentication authentication) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        if (resolvedAuth != null && !isPrivileged(resolvedAuth)) {
            String callerId = resolvedAuth.getName();
            if (callerId == null || !callerId.equals(request.userId())) {
                throw new AccessDeniedException("Cannot create order for another user");
            }
        }
        String idempotencyKey = request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
            ? request.idempotencyKey()
            : UUID.randomUUID().toString();
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(UUID.fromString(request.userId()))
            .storeId(request.storeId())
            .items(request.items())
            .subtotalCents(request.subtotalCents())
            .discountCents(request.discountCents())
            .totalCents(request.totalCents())
            .currency(request.currency())
            .couponCode(request.couponCode())
            .reservationId(parseOptionalUuid(request.reservationId()))
            .paymentId(parseOptionalUuid(request.paymentId()))
            .idempotencyKey(idempotencyKey)
            .deliveryAddress(request.effectiveDeliveryAddress())
            .dropoffLat(request.dropoffLat())
            .dropoffLng(request.dropoffLng())
            .quoteId(parseOptionalUuid(request.quoteId()))
            .quoteToken(request.quoteToken())
            .build();

        String orderId = orderService.createOrder(command);
        return new OrderCreationResult(orderId, 15);
    }

    public OrderCreationResult createOrder(OrderCreateRequest request) {
        return createOrder(request, null);
    }

    @GetMapping
    public Page<OrderSummaryResponse> listOrders(@AuthenticationPrincipal String principal,
                                                 Authentication authentication,
                                                 Pageable pageable) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        UUID userId = toUserId(resolvePrincipal(principal, resolvedAuth));
        if (userId == null) {
            throw new AccessDeniedException("User principal required to list orders");
        }
        return orderService.listOrders(userId, pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id,
                                  @AuthenticationPrincipal String principal,
                                  Authentication authentication) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        String resolvedPrincipal = resolvePrincipal(principal, resolvedAuth);
        return orderService.getOrder(id, toUserId(resolvedPrincipal), isPrivileged(resolvedAuth));
    }

    @GetMapping("/{id}/status")
    public OrderStatusResponse getOrderStatus(@PathVariable UUID id,
                                              @AuthenticationPrincipal String principal,
                                              Authentication authentication) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        String resolvedPrincipal = resolvePrincipal(principal, resolvedAuth);
        return orderService.getOrderStatus(id, toUserId(resolvedPrincipal), isPrivileged(resolvedAuth));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable UUID id,
                            @Valid @RequestBody CancelOrderRequest request,
                            @AuthenticationPrincipal String principal,
                            Authentication authentication) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        String resolvedPrincipal = resolvePrincipal(principal, resolvedAuth);
        if (isPrivileged(resolvedAuth)) {
            String changedBy = resolvedPrincipal != null ? resolvedPrincipal : "internal-service";
            orderService.cancelOrder(id, request.reason(), changedBy, null);
            return;
        }
        UUID userId = toUserId(resolvedPrincipal);
        if (userId == null) {
            throw new AccessDeniedException("User principal required to cancel order");
        }
        orderService.cancelOrderByUser(id, userId, request.reason());
    }

    @PostMapping("/{id}/amend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateAmendment(@PathVariable UUID id,
                                  @AuthenticationPrincipal String principal,
                                  Authentication authentication) {
        Authentication resolvedAuth = resolveAuthentication(authentication);
        String resolvedPrincipal = resolvePrincipal(principal, resolvedAuth);
        orderService.validateOrderAmendmentWindow(id, toUserId(resolvedPrincipal), isPrivileged(resolvedAuth));
    }

    private UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private Authentication resolveAuthentication(Authentication authentication) {
        Authentication resolved = authentication != null
            ? authentication
            : SecurityContextHolder.getContext().getAuthentication();
        if (resolved == null || !resolved.isAuthenticated() || resolved instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return resolved;
    }

    private String resolvePrincipal(String principal, Authentication authentication) {
        if (principal != null && !principal.isBlank()) {
            return principal;
        }
        return authentication != null ? authentication.getName() : null;
    }

    private UUID toUserId(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isPrivileged(Authentication authentication) {
        return isAdmin(authentication) || isInternalService(authentication);
    }

    private boolean isAdmin(Authentication authentication) {
        return hasAuthority(authentication, "ROLE_ADMIN");
    }

    private boolean isInternalService(Authentication authentication) {
        return hasAuthority(authentication, "ROLE_INTERNAL_SERVICE");
    }

    private boolean hasAuthority(Authentication authentication, String expected) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }
}
