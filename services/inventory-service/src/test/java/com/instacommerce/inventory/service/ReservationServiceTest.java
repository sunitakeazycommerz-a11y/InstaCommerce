package com.instacommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.inventory.client.WarehouseClient;
import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.exception.StoreInactiveException;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private OutboxService outboxService;

    @Mock
    private WarehouseClient warehouseClient;

    private RecordingTransactionManager transactionManager;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("warehouseClient", warehouseClient);
        ObjectProvider<WarehouseClient> provider = beanFactory.getBeanProvider(WarehouseClient.class);
        transactionManager = new RecordingTransactionManager();
        reservationService = new ReservationService(
            reservationRepository,
            stockItemRepository,
            new ReservationProperties(),
            entityManager,
            outboxService,
            new InventoryProperties(),
            provider,
            transactionManager
        );
    }

    @Test
    @DisplayName("reserve executes validateStoreActive and isStoreOpen outside transaction before beginning DB transaction")
    void reserveExecutesStoreChecksOutsideTransaction() throws NoSuchMethodException {
        Method reserveMethod = ReservationService.class.getMethod("reserve", ReserveRequest.class);
        assertThat(reserveMethod.isAnnotationPresent(Transactional.class)).isFalse();

        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        AtomicBoolean txActiveDuringValidate = new AtomicBoolean(true);
        AtomicBoolean txActiveDuringIsOpen = new AtomicBoolean(true);
        AtomicBoolean txActiveDuringSave = new AtomicBoolean(false);

        doAnswer(inv -> {
            txActiveDuringValidate.set(TransactionSynchronizationManager.isActualTransactionActive());
            assertThat(transactionManager.beginCount).isZero();
            return null;
        }).when(warehouseClient).validateStoreActive(storeId);

        when(warehouseClient.isStoreOpen(storeId)).thenAnswer(inv -> {
            txActiveDuringIsOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
            assertThat(transactionManager.beginCount).isZero();
            return true;
        });

        StockItem stockItem = new StockItem();
        stockItem.setProductId(productId);
        stockItem.setStoreId(storeId);
        stockItem.setOnHand(20);
        stockItem.setReserved(0);
        stubLockStockItem(productId, storeId, stockItem);

        when(reservationRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            txActiveDuringSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });

        ReserveResponse response = reservationService.reserve(
            new ReserveRequest("idem-1", storeId, List.of(new InventoryItemRequest(productId, 3)))
        );

        assertThat(response.reservationId()).isEqualTo(reservationId);
        assertThat(txActiveDuringValidate.get()).isFalse();
        assertThat(txActiveDuringIsOpen.get()).isFalse();
        assertThat(txActiveDuringSave.get()).isTrue();
        assertThat(transactionManager.beginCount).isEqualTo(1);
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.lastIsolationLevel).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);

        InOrder order = inOrder(warehouseClient, reservationRepository, stockItemRepository);
        order.verify(warehouseClient).validateStoreActive(storeId);
        order.verify(warehouseClient).isStoreOpen(storeId);
        order.verify(reservationRepository).findByIdempotencyKey("idem-1");
        order.verify(stockItemRepository).save(stockItem);
        order.verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("reserve rejects closed store with zero DB transactions begun")
    void reserveRejectsClosedStoreWithZeroDbTransactionsBegun() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(warehouseClient.isStoreOpen(storeId)).thenReturn(false);

        ReserveRequest request = new ReserveRequest(
            "idem-closed",
            storeId,
            List.of(new InventoryItemRequest(productId, 1))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(StoreInactiveException.class)
            .hasMessageContaining("CLOSED");

        verify(warehouseClient).validateStoreActive(storeId);
        verify(warehouseClient).isStoreOpen(storeId);
        assertThat(transactionManager.beginCount).isZero();
        verifyNoInteractions(reservationRepository, stockItemRepository, entityManager, outboxService);
    }

    @Test
    @DisplayName("reserve rejects inactive store with zero DB transactions begun and skips isStoreOpen")
    void reserveRejectsInactiveStoreWithoutDbTransaction() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        doThrow(new StoreInactiveException(storeId, "MAINTENANCE"))
            .when(warehouseClient).validateStoreActive(storeId);

        ReserveRequest request = new ReserveRequest(
            "idem-inactive",
            storeId,
            List.of(new InventoryItemRequest(productId, 1))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(StoreInactiveException.class);

        verify(warehouseClient, never()).isStoreOpen(any());
        assertThat(transactionManager.beginCount).isZero();
        verifyNoInteractions(reservationRepository, stockItemRepository, entityManager, outboxService);
    }

    @Test
    @DisplayName("reserve returns idempotently inside transaction without modifying stock")
    void reserveReturnsIdempotentlyInsideTransaction() {
        UUID storeId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Reservation existing = new Reservation();
        existing.setId(reservationId);
        existing.setStoreId(storeId);
        existing.setStatus(ReservationStatus.PENDING);
        existing.setExpiresAt(Instant.now().plusSeconds(120));

        when(warehouseClient.isStoreOpen(storeId)).thenReturn(true);
        when(reservationRepository.findByIdempotencyKey("idem-dup")).thenReturn(Optional.of(existing));

        ReserveResponse response = reservationService.reserve(
            new ReserveRequest("idem-dup", storeId, List.of(new InventoryItemRequest(UUID.randomUUID(), 2)))
        );

        assertThat(response.reservationId()).isEqualTo(reservationId);
        assertThat(transactionManager.beginCount).isEqualTo(1);
        assertThat(transactionManager.commitCount).isEqualTo(1);
        verify(stockItemRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("reserve rolls back transaction when available stock is insufficient")
    void reserveRollsBackOnInsufficientStock() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(warehouseClient.isStoreOpen(storeId)).thenReturn(true);
        when(reservationRepository.findByIdempotencyKey("idem-low")).thenReturn(Optional.empty());

        StockItem stockItem = new StockItem();
        stockItem.setProductId(productId);
        stockItem.setStoreId(storeId);
        stockItem.setOnHand(2);
        stockItem.setReserved(0);
        stubLockStockItem(productId, storeId, stockItem);

        assertThatThrownBy(() -> reservationService.reserve(
            new ReserveRequest("idem-low", storeId, List.of(new InventoryItemRequest(productId, 5)))
        )).isInstanceOf(InsufficientStockException.class);

        assertThat(transactionManager.beginCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
        assertThat(transactionManager.commitCount).isZero();
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubLockStockItem(UUID productId, UUID storeId, StockItem stockItem) {
        TypedQuery<StockItem> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(StockItem.class))).thenReturn(query);
        when(query.setParameter("pid", productId)).thenReturn(query);
        when(query.setParameter("sid", storeId)).thenReturn(query);
        when(query.setLockMode(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.lock.timeout"), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(stockItem);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int beginCount;
        int commitCount;
        int rollbackCount;
        int lastIsolationLevel;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            beginCount++;
            lastIsolationLevel = definition.getIsolationLevel();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
        }
    }
}
