package com.instacommerce.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.dto.request.OrderCreateRequest;
import com.instacommerce.order.dto.response.OrderCreationResult;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.exception.GlobalExceptionHandler;
import com.instacommerce.order.exception.TraceIdProvider;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.service.AuditLogService;
import com.instacommerce.order.service.OrderService;
import com.instacommerce.order.service.OutboxService;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("createOrder converts OrderCreateRequest to command and returns OrderCreationResult")
    void shouldCreateOrder() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String expectedOrderId = UUID.randomUUID().toString();

        when(orderService.createOrder(any(CreateOrderCommand.class))).thenReturn(expectedOrderId);

        OrderCreateRequest request = new OrderCreateRequest(
            userId.toString(),
            "store_1",
            List.of(new CartItem(productId, 2, 500L)),
            1000L,
            0L,
            50L,
            1050L,
            "INR",
            "SAVE10",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "addr_1",
            "123 Main St",
            "pm_card",
            null,
            null,
            "idemp_key_1"
        );

        OrderCreationResult result = controller.createOrder(request);

        assertThat(result.orderId()).isEqualTo(expectedOrderId);
        assertThat(result.estimatedDeliveryMinutes()).isEqualTo(15);

        ArgumentCaptor<CreateOrderCommand> commandCaptor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(orderService).createOrder(commandCaptor.capture());
        CreateOrderCommand cmd = commandCaptor.getValue();
        assertThat(cmd.getUserId()).isEqualTo(userId);
        assertThat(cmd.getStoreId()).isEqualTo("store_1");
        assertThat(cmd.getTotalCents()).isEqualTo(1050L);
        assertThat(cmd.getDeliveryAddress()).isEqualTo("123 Main St");
        assertThat(cmd.getCouponCode()).isEqualTo("SAVE10");
    }

    @Test
    @DisplayName("cancelOrder for internal service calls orderService.cancelOrder with service principal")
    void shouldCancelOrderForInternalService() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "checkout-orchestrator-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
        UUID orderId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("CHECKOUT_SAGA_ROLLBACK");

        controller.cancelOrder(orderId, request, "checkout-orchestrator-service", auth);

        verify(orderService).cancelOrder(eq(orderId), eq("CHECKOUT_SAGA_ROLLBACK"), eq("checkout-orchestrator-service"), eq(null));
    }

    @Test
    @DisplayName("cancelOrder for regular user calls orderService.cancelOrderByUser with user UUID")
    void shouldCancelOrderForUser() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UUID orderId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("Customer request");

        controller.cancelOrder(orderId, request, userId.toString(), auth);

        verify(orderService).cancelOrderByUser(eq(orderId), eq(userId), eq("Customer request"));
    }

    @Test
    @DisplayName("createOrder generates non-blank UUID when idempotencyKey is blank")
    void shouldGenerateIdempotencyKeyWhenBlank() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(orderService.createOrder(any(CreateOrderCommand.class))).thenReturn(UUID.randomUUID().toString());

        OrderCreateRequest request = new OrderCreateRequest(
            userId.toString(),
            "store_1",
            List.of(new CartItem(productId, 1, 500L)),
            500L,
            0L,
            0L,
            500L,
            "INR",
            null,
            null,
            null,
            "addr_1",
            "123 Main St",
            "pm_card",
            null,
            null,
            "   "
        );

        controller.createOrder(request);

        ArgumentCaptor<CreateOrderCommand> commandCaptor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(orderService).createOrder(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getIdempotencyKey()).isNotBlank();
        assertThat(UUID.fromString(commandCaptor.getValue().getIdempotencyKey())).isNotNull();
    }

    @Test
    @DisplayName("createOrder rejects user creating order for another user")
    void shouldRejectCreateOrderForAnotherUser() throws Exception {
        OrderService orderService = mock(OrderService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
            .setControllerAdvice(new GlobalExceptionHandler(new TraceIdProvider()))
            .build();

        UUID callerId = UUID.randomUUID();
        UUID victimUserId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
            callerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        OrderCreateRequest request = new OrderCreateRequest(
            victimUserId.toString(),
            "store_1",
            List.of(new CartItem(UUID.randomUUID(), 1, 500L)),
            500L,
            0L,
            0L,
            500L,
            "INR",
            null,
            null,
            null,
            "addr_1",
            "123 Main St",
            "pm_card",
            null,
            null,
            "idem-key"
        );

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/amend allows order owner when PLACED")
    void shouldAllowOwnerToAmendPlacedOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Order order = placedOrder(orderId, ownerId);
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        MockMvc mockMvc = standaloneMockMvcWithOrderService(orderRepository);
        Authentication auth = new UsernamePasswordAuthenticationToken(
            ownerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/api/v1/orders/{id}/amend", orderId)
                .principal(auth))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /orders/{id}/amend rejects non-owner with 403 ACCESS_DENIED")
    void shouldRejectNonOwnerAmendmentWith403() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        Order order = placedOrder(orderId, ownerId);
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        MockMvc mockMvc = standaloneMockMvcWithOrderService(orderRepository);
        Authentication auth = new UsernamePasswordAuthenticationToken(
            attackerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/orders/{id}/amend", orderId)
                .principal(auth))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/amend rejects non-owner with 403 even when order is PACKING")
    void shouldRejectNonOwnerAmendmentWith403BeforeStatusLeak() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        Order order = placedOrder(orderId, ownerId);
        order.setStatus(OrderStatus.PACKING);
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        MockMvc mockMvc = standaloneMockMvcWithOrderService(orderRepository);
        Authentication auth = new UsernamePasswordAuthenticationToken(
            attackerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/api/v1/orders/{id}/amend", orderId)
                .principal(auth))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/amend allows ADMIN and INTERNAL_SERVICE roles")
    void shouldAllowAdminAndInternalServiceToAmendOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Order order = placedOrder(orderId, ownerId);
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        MockMvc mockMvc = standaloneMockMvcWithOrderService(orderRepository);
        Authentication adminAuth = new UsernamePasswordAuthenticationToken(
            UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        Authentication serviceAuth = new UsernamePasswordAuthenticationToken(
            "fulfillment-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));

        mockMvc.perform(post("/api/v1/orders/{id}/amend", orderId)
                .principal(adminAuth))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/orders/{id}/amend", orderId)
                .principal(serviceAuth))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} and /status routes resolve and allow INTERNAL_SERVICE")
    void shouldServeCanonicalApiV1OrderRoutes() throws Exception {
        OrderService orderService = mock(OrderService.class);
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderResponse orderResponse = new OrderResponse(
            orderId, userId, "store_1", OrderStatus.PLACED.name(), List.of(), 1000L, 0L, 1000L, "INR",
            null, Instant.now(), List.of());
        OrderStatusResponse statusResponse = new OrderStatusResponse(orderId, OrderStatus.PLACED.name(), List.of());
        when(orderService.getOrder(orderId, null, true)).thenReturn(orderResponse);
        when(orderService.getOrderStatus(orderId, null, true)).thenReturn(statusResponse);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
            .setControllerAdvice(new GlobalExceptionHandler(new TraceIdProvider()))
            .build();
        Authentication serviceAuth = new UsernamePasswordAuthenticationToken(
            "ai-orchestrator-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId).principal(serviceAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId.toString()));

        mockMvc.perform(get("/api/v1/orders/{id}/status", orderId).principal(serviceAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLACED"));
    }

    private MockMvc standaloneMockMvcWithOrderService(OrderRepository orderRepository) {
        OrderService realOrderService = new OrderService(
            orderRepository,
            mock(OrderStatusHistoryRepository.class),
            mock(OutboxService.class),
            mock(AuditLogService.class),
            mock(PricingQuoteClient.class)
        );
        return MockMvcBuilders.standaloneSetup(new OrderController(realOrderService))
            .setControllerAdvice(new GlobalExceptionHandler(new TraceIdProvider()))
            .build();
    }

    private Order placedOrder(UUID orderId, UUID userId) {
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStoreId("store_1");
        order.setStatus(OrderStatus.PLACED);
        order.setCurrency("INR");
        order.setTotalCents(1000L);
        return order;
    }
}
