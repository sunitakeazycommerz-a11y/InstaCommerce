package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(OrderCreateTransactionBoundaryTest.TestConfig.class)
class OrderCreateTransactionBoundaryTest {

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        OrderRepository orderRepository() {
            return mock(OrderRepository.class);
        }

        @Bean
        OrderStatusHistoryRepository statusHistoryRepository() {
            return mock(OrderStatusHistoryRepository.class);
        }

        @Bean
        OutboxService outboxService() {
            return mock(OutboxService.class);
        }

        @Bean
        AuditLogService auditLogService() {
            return mock(AuditLogService.class);
        }

        @Bean
        PricingQuoteClient pricingQuoteClient() {
            return mock(PricingQuoteClient.class);
        }

        @Bean
        OrderService orderService(OrderRepository orderRepository,
                                  OrderStatusHistoryRepository statusHistoryRepository,
                                  OutboxService outboxService,
                                  AuditLogService auditLogService,
                                  PricingQuoteClient pricingQuoteClient) {
            return new OrderService(orderRepository, statusHistoryRepository, outboxService,
                auditLogService, pricingQuoteClient);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger begunCount = new AtomicInteger();
        final AtomicInteger committedCount = new AtomicInteger();
        final AtomicInteger rolledBackCount = new AtomicInteger();
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

        void reset() {
            begunCount.set(0);
            committedCount.set(0);
            rolledBackCount.set(0);
            active.remove();
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begunCount.incrementAndGet();
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committedCount.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBackCount.incrementAndGet();
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.remove();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return Boolean.TRUE.equals(active.get());
        }
    }

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OutboxService outboxService;
    @Autowired private PricingQuoteClient pricingQuoteClient;
    @Autowired private RecordingTransactionManager transactionManager;

    @BeforeEach
    void resetState() {
        reset(orderRepository, AopTestUtils.<OutboxService>getTargetObject(outboxService), pricingQuoteClient);
        transactionManager.reset();
    }

    @Test
    @DisplayName("pricingQuoteClient.validateQuote runs outside @Transactional while order save + outbox run inside one transaction")
    void validatesQuoteOutsideTransactionAndPersistsAtomicallyInsideTransaction() {
        UUID quoteId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        List<Boolean> txActiveStates = new ArrayList<>();

        when(orderRepository.findByIdempotencyKey("checkout-wf1:order:create")).thenReturn(Optional.empty());
        when(pricingQuoteClient.validateQuote(quoteId, "token-1", 800L, 1000L, 200L))
            .thenAnswer(inv -> {
                txActiveStates.add(TransactionSynchronizationManager.isActualTransactionActive());
                return true;
            });
        when(orderRepository.save(any(Order.class)))
            .thenAnswer(inv -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                Order saved = inv.getArgument(0);
                saved.setId(orderId);
                return saved;
            });

        CreateOrderCommand command = commandBuilder("checkout-wf1:order:create", quoteId, "token-1").build();
        String result = orderService.createOrder(command);

        assertThat(result).isEqualTo(orderId.toString());
        assertThat(txActiveStates).containsExactly(false);
        assertThat(transactionManager.begunCount.get()).isEqualTo(1);
        assertThat(transactionManager.committedCount.get()).isEqualTo(1);
        verify(AopTestUtils.<OutboxService>getTargetObject(outboxService))
            .publish(eq("Order"), eq(orderId.toString()), eq("OrderCreated"), any());
    }

    @Test
    @DisplayName("quote validation failure never opens a persistence transaction")
    void quoteFailureDoesNotOpenPersistenceTransaction() {
        UUID quoteId = UUID.randomUUID();
        when(orderRepository.findByIdempotencyKey("checkout-wf2:order:create")).thenReturn(Optional.empty());
        when(pricingQuoteClient.validateQuote(quoteId, "bad-token", 800L, 1000L, 200L)).thenReturn(false);

        CreateOrderCommand command = commandBuilder("checkout-wf2:order:create", quoteId, "bad-token").build();

        assertThatThrownBy(() -> orderService.createOrder(command))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(transactionManager.begunCount.get()).isZero();
    }

    @Test
    @DisplayName("oversized idempotencyKey (>64 chars) is deterministically normalized to 64 chars and recovers on unique conflict")
    void normalizesOversizedIdempotencyKeyAndRecoversOnUniqueConflict() {
        String longKey = "checkout-" + UUID.randomUUID() + "-" + UUID.randomUUID() + ":order:create";
        assertThat(longKey.length()).isGreaterThan(64);

        UUID existingOrderId = UUID.randomUUID();
        Order existingOrder = new Order();
        existingOrder.setId(existingOrderId);
        existingOrder.setStatus(OrderStatus.PENDING);

        when(orderRepository.findByIdempotencyKey(any()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existingOrder));
        when(orderRepository.save(any(Order.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate uq_order_idempotency"));

        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(UUID.randomUUID())
            .storeId("store-1")
            .items(List.of(new CartItem(UUID.randomUUID(), 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(0L)
            .totalCents(1000L)
            .idempotencyKey(longKey)
            .build();

        String resolvedId = orderService.createOrder(command);

        assertThat(resolvedId).isEqualTo(existingOrderId.toString());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderRepository, atLeast(2)).findByIdempotencyKey(keyCaptor.capture());
        String normalizedKey = keyCaptor.getAllValues().get(0);
        assertThat(normalizedKey).hasSize(64);
        assertThat(keyCaptor.getAllValues()).containsOnly(normalizedKey);
    }

    private CreateOrderCommand.Builder commandBuilder(String idempotencyKey, UUID quoteId, String quoteToken) {
        return CreateOrderCommand.builder()
            .userId(UUID.randomUUID())
            .storeId("store-1")
            .items(List.of(new CartItem(UUID.randomUUID(), 1, 1000L)))
            .subtotalCents(1000L)
            .discountCents(200L)
            .totalCents(800L)
            .quoteId(quoteId)
            .quoteToken(quoteToken)
            .idempotencyKey(idempotencyKey);
    }
}
