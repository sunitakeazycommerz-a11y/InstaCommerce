package com.instacommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.inventory.client.WarehouseClient;
import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import com.instacommerce.inventory.domain.model.AsnLineItem;
import com.instacommerce.inventory.domain.model.InboundAsn;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.SafetyStockItemRequest;
import com.instacommerce.inventory.dto.response.AsnResponse;
import com.instacommerce.inventory.exception.ReservationExpiredException;
import com.instacommerce.inventory.repository.InboundAsnRepository;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import com.instacommerce.inventory.repository.StockBatchRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class InventoryRemediationTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private OutboxService outboxService;

    @Mock
    private InboundAsnRepository asnRepository;

    @Mock
    private StockBatchRepository batchRepository;

    @Mock
    private SkuSafetyStockBufferRepository bufferRepository;

    @Test
    @DisplayName("confirm and cancel declare noRollbackFor ReservationExpiredException and persist EXPIRED")
    void expiredReservationMarksExpiredWithoutRollback() throws NoSuchMethodException {
        Transactional confirmTx = ReservationService.class.getMethod("confirm", UUID.class)
            .getAnnotation(Transactional.class);
        Transactional cancelTx = ReservationService.class.getMethod("cancel", UUID.class)
            .getAnnotation(Transactional.class);
        assertThat(confirmTx.noRollbackFor()).contains(ReservationExpiredException.class);
        assertThat(cancelTx.noRollbackFor()).contains(ReservationExpiredException.class);

        UUID reservationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setStoreId(storeId);
        reservation.setIdempotencyKey("order-1");
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setExpiresAt(Instant.now().minusSeconds(60));
        ReservationLineItem line = new ReservationLineItem();
        line.setReservation(reservation);
        line.setProductId(productId);
        line.setQuantity(3);
        reservation.getLineItems().add(line);

        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(10);
        stock.setReserved(3);

        TypedQuery<StockItem> query = mock();
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(entityManager.createQuery(any(String.class), eq(StockItem.class))).thenReturn(query);
        when(query.setParameter("pid", productId)).thenReturn(query);
        when(query.setParameter("sid", storeId)).thenReturn(query);
        when(query.setLockMode(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.lock.timeout"), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(stock);

        ReservationService service = new ReservationService(
            reservationRepository, stockItemRepository, new ReservationProperties(),
            entityManager, outboxService, new InventoryProperties(),
            new StaticListableBeanFactory().getBeanProvider(WarehouseClient.class),
            null, null, null, null
        );

        assertThatThrownBy(() -> service.confirm(reservationId))
            .isInstanceOf(ReservationExpiredException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stock.getReserved()).isZero();
        verify(reservationRepository).save(reservation);
    }

    @Test
    @DisplayName("reconcileStock compares physical vs onHand and unfreezeStock clamps below reserved")
    void reconciliationComparesOnHandAndClampsBelowReserved() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(50);
        stock.setReserved(10);

        when(stockItemRepository.findByStoreIdAndProductIdIn(storeId, List.of(productId)))
            .thenReturn(List.of(stock));
        when(stockItemRepository.findByProductIdAndStoreIdForUpdate(productId, storeId))
            .thenReturn(Optional.of(stock));

        StockReconciliationService service = new StockReconciliationService(stockItemRepository, outboxService);
        var summary = service.reconcileStock(storeId, List.of(
            new StockReconciliationService.PhysicalStockCount(productId, 47)
        ));
        assertThat(summary.discrepancies()).hasSize(1);
        assertThat(summary.discrepancies().get(0).systemStock()).isEqualTo(50);
        assertThat(summary.discrepancies().get(0).driftQuantity()).isEqualTo(-3);

        service.unfreezeStock(productId, storeId, 4);
        assertThat(stock.getOnHand()).isEqualTo(10);
        verify(outboxService).publish(eq("StockItem"), eq(productId.toString()),
            eq("StockReservedShortfallDetected"), any());
    }

    @Test
    @DisplayName("InboundReceivingService preserves partial accepted quantities and SafetyStockService honors cooldown")
    void inboundPartialQuantityAndSafetyCooldown() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        InboundAsn asn = new InboundAsn();
        asn.setId(asnId);
        asn.setStoreId(storeId);
        asn.setPalletBarcode("PALLET-PARTIAL");
        AsnLineItem line = new AsnLineItem();
        line.setProductId(productId);
        line.setExpectedQuantity(50);
        asn.addLineItem(line);

        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(10);
        stock.setReserved(0);

        when(asnRepository.findByPalletBarcode("PALLET-PARTIAL")).thenReturn(Optional.of(asn));
        when(stockItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(stock));
        when(asnRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InboundReceivingService receivingService = new InboundReceivingService(
            asnRepository, stockItemRepository, batchRepository, outboxService, new ObjectMapper());
        AsnResponse received = receivingService.receiveByPalletBarcode(
            "PALLET-PARTIAL", "OP-1", Map.of(productId, 32));
        assertThat(received.items().get(0).receivedQuantity()).isEqualTo(32);
        assertThat(stock.getOnHand()).isEqualTo(42);

        SkuSafetyStockBuffer buffer = new SkuSafetyStockBuffer();
        buffer.setStoreId(storeId);
        buffer.setProductId(productId);
        buffer.setLastReorderEventAt(Instant.now().minusSeconds(300));
        when(bufferRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(buffer));
        when(bufferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(stock));

        SafetyStockService safetyService = new SafetyStockService(
            bufferRepository, stockItemRepository, outboxService);
        safetyService.ingestBuffer(storeId, new SafetyStockItemRequest(
            productId, new BigDecimal("1.65"), new BigDecimal("20.00"), 4));
        verify(outboxService, never()).publish(eq("INVENTORY_SAFETY_STOCK"), any(), eq("ReorderPointReached"), any());
    }
}
