package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrderDiscountValidationTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository statusHistoryRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PricingQuoteClient pricingQuoteClient;

    private OrderService orderService;
    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, statusHistoryRepository, outboxService, auditLogService, pricingQuoteClient);
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Rejects order when subtotal minus discount does not match total")
    void rejectsMismatchedOrderTotal() {
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .totalCents(500L) // Incorrect: 1000 - 200 = 800, not 500
            .idempotencyKey("idem-1")
            .build();

        assertThatThrownBy(() -> orderService.createOrder(command))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getCode()).isEqualTo("INVALID_ORDER_TOTAL");
            });

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects order when discount > 0 without quote token")
    void rejectsDiscountWithoutQuoteToken() {
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .totalCents(800L)
            .idempotencyKey("idem-2")
            // missing quoteId and quoteToken
            .build();

        assertThatThrownBy(() -> orderService.createOrder(command))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getCode()).isEqualTo("DISCOUNT_REQUIRES_QUOTE");
            });

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects order when pricing service reports invalid quote token")
    void rejectsInvalidQuoteToken() {
        UUID quoteId = UUID.randomUUID();
        String quoteToken = "forged-or-expired-token";

        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .totalCents(800L)
            .quoteId(quoteId)
            .quoteToken(quoteToken)
            .idempotencyKey("idem-3")
            .build();

        when(pricingQuoteClient.validateQuote(quoteId, quoteToken, 800L, 1000L, 200L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(command))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(apiEx.getCode()).isEqualTo("QUOTE_VALIDATION_FAILED");
            });

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Accepts order with verified quote token and persists order")
    void acceptsOrderWithValidQuoteToken() {
        UUID quoteId = UUID.randomUUID();
        String quoteToken = "valid-hmac-token";
        UUID orderId = UUID.randomUUID();

        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .totalCents(800L)
            .quoteId(quoteId)
            .quoteToken(quoteToken)
            .idempotencyKey("idem-4")
            .build();

        when(pricingQuoteClient.validateQuote(quoteId, quoteToken, 800L, 1000L, 200L)).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(orderId);
            return o;
        });

        String createdId = orderService.createOrder(command);

        assertThat(createdId).isEqualTo(orderId.toString());
        verify(orderRepository).save(any(Order.class));
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCreated"), any());
    }
}
