package com.instacommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.inventory.client.WarehouseClient;
import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.ReservationReleaseLog;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockBatch;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.PartialReleaseResponse;
import com.instacommerce.inventory.dto.response.StockBatchResponse;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.exception.InvalidStockAdjustmentException;
import com.instacommerce.inventory.exception.StockFrozenException;
import com.instacommerce.inventory.repository.ReservationReleaseLogRepository;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockBatchRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(MockitoExtension.class)
class FefoAndPartialReleaseTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockBatchRepository stockBatchRepository;

    @Mock
    private ReservationReleaseLogRepository releaseLogRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private OutboxService outboxService;

    @Mock
    private WarehouseClient warehouseClient;

    private Clock fixedClock;
    private FefoBatchAllocationService fefoService;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        fefoService = new FefoBatchAllocationService(stockBatchRepository, fixedClock);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("warehouseClient", warehouseClient);
        ObjectProvider<WarehouseClient> warehouseProvider = beanFactory.getBeanProvider(WarehouseClient.class);

        InventoryProperties inventoryProperties = new InventoryProperties();
        StockLevelMonitor monitor = new StockLevelMonitor(inventoryProperties, outboxService);
        reservationService = new ReservationService(
            reservationRepository,
            stockItemRepository,
            new ReservationProperties(),
            entityManager,
            outboxService,
            inventoryProperties,
            warehouseProvider,
            new NoOpTransactionManager(),
            fefoService,
            monitor,
            releaseLogRepository
        );
    }

    @Test
    @DisplayName("resolveSellableAvailable excludes expired batches and reserve enforces non-expired quantity")
    void expiredBatchesExcludedFromSellableAvailability() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockItem stock = buildStock(storeId, productId, 10, 0);

        StockBatch expired = buildBatch(storeId, productId, "EXP-01", 7, NOW.minus(Duration.ofHours(2)));
        StockBatch valid = buildBatch(storeId, productId, "VAL-02", 3, NOW.plus(Duration.ofDays(2)));
        when(stockBatchRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(List.of(expired, valid));

        assertThat(fefoService.resolveSellableAvailable(stock)).isEqualTo(3);

        when(warehouseClient.isStoreOpen(storeId)).thenReturn(true);
        when(reservationRepository.findByIdempotencyKey("order-exp")).thenReturn(Optional.empty());
        stubLockStockItem(productId, storeId, stock);

        ReserveRequest request = new ReserveRequest(
            "order-exp",
            storeId,
            List.of(new InventoryItemRequest(productId, 5))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(InsufficientStockException.class);
        verify(stockItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserve rejects frozen stock items immediately")
    void reserveRejectsFrozenStock() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockItem frozen = buildStock(storeId, productId, 20, 0);
        frozen.setFrozen(true);

        when(warehouseClient.isStoreOpen(storeId)).thenReturn(true);
        when(reservationRepository.findByIdempotencyKey("order-frozen")).thenReturn(Optional.empty());
        stubLockStockItem(productId, storeId, frozen);

        ReserveRequest request = new ReserveRequest(
            "order-frozen",
            storeId,
            List.of(new InventoryItemRequest(productId, 2))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(StockFrozenException.class);
    }

    @Test
    @DisplayName("confirm deducts earliest expiring batches first and records batch allocations in StockConfirmed")
    @SuppressWarnings("unchecked")
    void confirmAllocatesBatchesInFefoOrder() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        StockItem stock = buildStock(storeId, productId, 10, 5);

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setIdempotencyKey("order-fefo");
        reservation.setStoreId(storeId);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        ReservationLineItem line = new ReservationLineItem();
        line.setReservation(reservation);
        line.setProductId(productId);
        line.setQuantity(5);
        reservation.getLineItems().add(line);

        StockBatch earliest = buildBatch(storeId, productId, "BATCH-EARLY", 3, NOW.plus(Duration.ofHours(12)));
        StockBatch later = buildBatch(storeId, productId, "BATCH-LATER", 6, NOW.plus(Duration.ofDays(3)));

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        stubLockStockItem(productId, storeId, stock);
        when(stockBatchRepository.existsByStoreIdAndProductId(storeId, productId)).thenReturn(true);
        when(stockBatchRepository.findSellableBatchesForFefoUpdate(storeId, productId, NOW))
            .thenReturn(List.of(earliest, later));
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reservationService.confirm(reservationId);

        assertThat(earliest.getQuantity()).isZero();
        assertThat(later.getQuantity()).isEqualTo(4);
        assertThat(stock.getOnHand()).isEqualTo(5);
        assertThat(stock.getReserved()).isZero();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(eq("Reservation"), eq(reservationId.toString()), eq("StockConfirmed"), payloadCaptor.capture());
        List<Map<String, Object>> items = (List<Map<String, Object>>) payloadCaptor.getValue().get("items");
        List<Map<String, Object>> batches = (List<Map<String, Object>>) items.get(0).get("batches");
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsEntry("batchNumber", "BATCH-EARLY").containsEntry("quantity", 3);
        assertThat(batches.get(1)).containsEntry("batchNumber", "BATCH-LATER").containsEntry("quantity", 2);
    }

    @Test
    @DisplayName("releasePartial releases subset of quantity, updates status on full release, and honors releaseKey idempotency")
    void releasePartialHandlesPartialFullValidationAndIdempotency() {
        UUID storeId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        StockItem stockA = buildStock(storeId, productA, 10, 4);
        StockItem stockB = buildStock(storeId, productB, 10, 3);

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setIdempotencyKey("order-partial");
        reservation.setStoreId(storeId);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        ReservationLineItem lineA = new ReservationLineItem();
        lineA.setReservation(reservation);
        lineA.setProductId(productA);
        lineA.setQuantity(4);
        ReservationLineItem lineB = new ReservationLineItem();
        lineB.setReservation(reservation);
        lineB.setProductId(productB);
        lineB.setQuantity(3);
        reservation.getLineItems().add(lineA);
        reservation.getLineItems().add(lineB);

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(releaseLogRepository.existsByReservationIdAndReleaseKey(reservationId, "rel-1")).thenReturn(false);
        stubLockStockItem(productA, storeId, stockA);
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PartialReleaseResponse first = reservationService.releasePartial(
            reservationId,
            List.of(new InventoryItemRequest(productA, 2)),
            "rel-1"
        );

        assertThat(first.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(stockA.getReserved()).isEqualTo(2);
        assertThat(first.remainingItems()).hasSize(2);
        verify(releaseLogRepository).save(any(ReservationReleaseLog.class));

        when(releaseLogRepository.existsByReservationIdAndReleaseKey(reservationId, "rel-1")).thenReturn(true);
        PartialReleaseResponse replay = reservationService.releasePartial(
            reservationId,
            List.of(new InventoryItemRequest(productA, 2)),
            "rel-1"
        );
        assertThat(replay.releasedItems()).isEmpty();
        assertThat(stockA.getReserved()).isEqualTo(2);

        assertThatThrownBy(() -> reservationService.releasePartial(
            reservationId,
            List.of(new InventoryItemRequest(productA, 5)),
            "rel-over"
        )).isInstanceOf(InvalidStockAdjustmentException.class);

        when(releaseLogRepository.existsByReservationIdAndReleaseKey(reservationId, "rel-all")).thenReturn(false);
        stubLockStockItemForMultiple(Map.of(productA, stockA, productB, stockB));
        PartialReleaseResponse finalRelease = reservationService.releasePartial(
            reservationId,
            List.of(new InventoryItemRequest(productA, 2), new InventoryItemRequest(productB, 3)),
            "rel-all"
        );
        assertThat(finalRelease.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(finalRelease.remainingItems()).isEmpty();
        assertThat(stockA.getReserved()).isZero();
        assertThat(stockB.getReserved()).isZero();
    }

    @Test
    @DisplayName("writeOffExpiredBatches zeroes expired batches and adjusts unreserved onHand")
    void writeOffExpiredBatchesDeductsUnreservedOnHand() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockBatch expired = buildBatch(storeId, productId, "EXP-OLD", 8, NOW.minus(Duration.ofHours(1)));
        expired.setId(UUID.randomUUID());
        StockItem stock = buildStock(storeId, productId, 10, 4);

        when(stockBatchRepository.findExpiredBatchesForWriteOff(eq(storeId), any(Instant.class)))
            .thenReturn(List.of(expired));
        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(stock));

        PerishableLiquidationService service = new PerishableLiquidationService(
            stockBatchRepository, stockItemRepository, outboxService);
        List<StockBatchResponse> writtenOff = service.writeOffExpiredBatches(storeId);

        assertThat(writtenOff).hasSize(1);
        assertThat(expired.getQuantity()).isZero();
        assertThat(stock.getOnHand()).isEqualTo(4);
        verify(outboxService).publish(eq("STOCK_BATCH"), eq(expired.getId().toString()), eq("ExpiredStockWrittenOff"), any());
    }

    private StockItem buildStock(UUID storeId, UUID productId, int onHand, int reserved) {
        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        return stock;
    }

    private StockBatch buildBatch(UUID storeId, UUID productId, String batchNumber, int qty, Instant expiry) {
        StockBatch batch = new StockBatch();
        batch.setStoreId(storeId);
        batch.setProductId(productId);
        batch.setBatchNumber(batchNumber);
        batch.setQuantity(qty);
        batch.setExpiryDate(expiry);
        return batch;
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

    @SuppressWarnings("unchecked")
    private void stubLockStockItemForMultiple(Map<UUID, StockItem> items) {
        TypedQuery<StockItem> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(StockItem.class))).thenReturn(query);
        final UUID[] requestedPid = new UUID[1];
        when(query.setParameter(eq("pid"), any())).thenAnswer(inv -> {
            requestedPid[0] = inv.getArgument(1);
            return query;
        });
        when(query.setParameter(eq("sid"), any())).thenReturn(query);
        when(query.setLockMode(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.lock.timeout"), any())).thenReturn(query);
        when(query.getSingleResult()).thenAnswer(inv -> items.get(requestedPid[0]));
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
