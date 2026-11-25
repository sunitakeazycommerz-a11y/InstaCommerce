package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.springframework.transaction.PlatformTransactionManager;

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
        OrderCreationService creationService = new OrderCreationService(
            orderRepository, statusHistoryRepository, outboxService,
            pricingQuoteClient, mock(PlatformTransactionManager.class));
        OrderLifecycleService lifecycleService = new OrderLifecycleService(
            orderRepository, statusHistoryRepository, outboxService, auditLogService, null, null, null);
        OrderModificationService modificationService = new OrderModificationService(
            orderRepository, outboxService, auditLogService, lifecycleService);
        orderService = new OrderService(creationService, lifecycleService, modificationService);
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

        when(pricingQuoteClient.validateQuote(userId, quoteId, quoteToken, 800L, 1000L, 200L)).thenReturn(false);

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

        when(pricingQuoteClient.validateQuote(userId, quoteId, quoteToken, 800L, 1000L, 200L)).thenReturn(true);
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

    @Test
    @DisplayName("Accepts zero-total 100%-discounted order with deliveryFeeCents validation")
    void acceptsZeroTotalOrderAndDeliveryFee() {
        UUID quoteId = UUID.randomUUID();
        UUID preGeneratedOrderId = UUID.randomUUID();
        CreateOrderCommand command = CreateOrderCommand.builder()
            .orderId(preGeneratedOrderId)
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(1000L)
            .deliveryFeeCents(0L)
            .totalCents(0L)
            .quoteId(quoteId)
            .quoteToken("valid-token")
            .idempotencyKey("idem-zero")
            .build();

        when(pricingQuoteClient.validateQuote(userId, quoteId, "valid-token", 0L, 1000L, 1000L)).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        String createdId = orderService.createOrder(command);

        assertThat(createdId).isEqualTo(preGeneratedOrderId.toString());
    }

    @Test
    @DisplayName("Accepts order when max(0, subtotal - discount) + deliveryFee equals total")
    void acceptsOrderWithDeliveryFee() {
        UUID quoteId = UUID.randomUUID();
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .deliveryFeeCents(50L)
            .totalCents(850L)
            .quoteId(quoteId)
            .quoteToken("valid-token")
            .idempotencyKey("idem-fee")
            .build();

        when(pricingQuoteClient.validateQuote(userId, quoteId, "valid-token", 850L, 1000L, 200L)).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        String createdId = orderService.createOrder(command);
        assertThat(createdId).isNotBlank();
    }

    @Test
    @DisplayName("Rejects order when subtotal does not match sum of item line totals or lineTotal mismatches unitPrice*qty")
    void rejectsSubtotalAndLineTotalTampering() {
        CreateOrderCommand mismatchedSubtotal = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 2, 1000L)))
            .subtotalCents(500L)
            .totalCents(500L)
            .idempotencyKey("idem-tampered-subtotal")
            .build();

        assertThatThrownBy(() -> orderService.createOrder(mismatchedSubtotal))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_ORDER_TOTAL"));

        CreateOrderCommand mismatchedLineTotal = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, "Item", "SKU-1", 2, 1000L, 1500L)))
            .subtotalCents(1500L)
            .totalCents(1500L)
            .idempotencyKey("idem-tampered-line")
            .build();

        assertThatThrownBy(() -> orderService.createOrder(mismatchedLineTotal))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_ORDER_TOTAL"));
    }

    @Test
    @DisplayName("Rejects half-present quote without discount and validates full quote even when discountCents == 0")
    void enforcesQuoteIntegrityEvenWhenDiscountIsZero() {
        UUID quoteId = UUID.randomUUID();
        CreateOrderCommand halfQuote = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(0L)
            .totalCents(1000L)
            .quoteId(quoteId)
            .idempotencyKey("idem-half-quote")
            .build();

        assertThatThrownBy(() -> orderService.createOrder(halfQuote))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_QUOTE"));

        CreateOrderCommand fullZeroDiscountQuote = CreateOrderCommand.builder()
            .userId(userId)
            .storeId("store-1")
            .items(List.of(new CartItem(productId, 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(0L)
            .totalCents(1000L)
            .quoteId(quoteId)
            .quoteToken("zero-discount-token")
            .idempotencyKey("idem-zero-disc-quote")
            .build();

        when(pricingQuoteClient.validateQuote(userId, quoteId, "zero-discount-token", 1000L, 1000L, 0L))
            .thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(fullZeroDiscountQuote))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("QUOTE_VALIDATION_FAILED"));
    }
}
