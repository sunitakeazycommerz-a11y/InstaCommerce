package com.instacommerce.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.inventory.domain.model.AsnLineItem;
import com.instacommerce.inventory.domain.model.AsnStatus;
import com.instacommerce.inventory.domain.model.InboundAsn;
import com.instacommerce.inventory.domain.model.StockBatch;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.AsnIngestRequest;
import com.instacommerce.inventory.dto.response.AsnResponse;
import com.instacommerce.inventory.repository.InboundAsnRepository;
import com.instacommerce.inventory.repository.StockBatchRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboundReceivingServiceTest {

    @Mock
    private InboundAsnRepository asnRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockBatchRepository batchRepository;

    @Mock
    private OutboxService outboxService;

    private ObjectMapper objectMapper;
    private InboundReceivingService receivingService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        receivingService = new InboundReceivingService(
            asnRepository,
            stockItemRepository,
            batchRepository,
            outboxService,
            objectMapper
        );
    }

    @Test
    @DisplayName("Ingest ASN from supplier EDI 856 / JSON payload")
    void shouldIngestAsn() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        AsnIngestRequest.AsnItemRequest item = new AsnIngestRequest.AsnItemRequest(
            productId, 100, "BATCH-2026", Instant.now().plusSeconds(86400 * 14)
        );
        AsnIngestRequest request = new AsnIngestRequest(
            "ASN-99001", "PO-5501", "VENDOR-AMUL", storeId, "PALLET-BARCODE-001", List.of(item)
        );

        when(asnRepository.findByAsnNumber("ASN-99001")).thenReturn(Optional.empty());
        when(asnRepository.findByPalletBarcode("PALLET-BARCODE-001")).thenReturn(Optional.empty());
        when(asnRepository.save(any(InboundAsn.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AsnResponse response = receivingService.ingestAsn(request);

        assertNotNull(response);
        assertEquals("ASN-99001", response.asnNumber());
        assertEquals("PALLET-BARCODE-001", response.palletBarcode());
        assertEquals(AsnStatus.PENDING, response.status());
        assertEquals(1, response.items().size());
        assertEquals(100, response.items().get(0).expectedQuantity());
    }

    @Test
    @DisplayName("One-click pallet barcode scan receipts shipment and reconciles inventory and stock batches")
    void shouldReceiveByPalletBarcodeAndReconcileStock() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        InboundAsn asn = new InboundAsn();
        asn.setId(asnId);
        asn.setAsnNumber("ASN-99002");
        asn.setPurchaseOrderId("PO-5502");
        asn.setVendorId("VENDOR-NESTLE");
        asn.setStoreId(storeId);
        asn.setPalletBarcode("PALLET-BARCODE-002");
        asn.setStatus(AsnStatus.PENDING);

        AsnLineItem lineItem = new AsnLineItem();
        lineItem.setProductId(productId);
        lineItem.setExpectedQuantity(50);
        lineItem.setBatchNumber("BATCH-MAGGI-01");
        lineItem.setExpiryDate(Instant.now().plusSeconds(86400 * 60));
        asn.addLineItem(lineItem);

        when(asnRepository.findByPalletBarcode("PALLET-BARCODE-002")).thenReturn(Optional.of(asn));
        when(asnRepository.save(any(InboundAsn.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Mock existing stock item with 10 on hand
        StockItem existingStock = new StockItem();
        existingStock.setStoreId(storeId);
        existingStock.setProductId(productId);
        existingStock.setOnHand(10);
        existingStock.setReserved(0);
        when(stockItemRepository.findByProductIdAndStoreIdForUpdate(productId, storeId))
            .thenReturn(Optional.of(existingStock));
        when(stockItemRepository.save(any(StockItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(batchRepository.findByStoreIdAndProductIdAndBatchNumber(storeId, productId, "BATCH-MAGGI-01"))
            .thenReturn(Optional.empty());
        when(batchRepository.save(any(StockBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AsnResponse response = receivingService.receiveByPalletBarcode("PALLET-BARCODE-002", "OPERATOR_ALICE");

        assertNotNull(response);
        assertEquals(AsnStatus.RECONCILED, response.status());
        assertEquals("OPERATOR_ALICE", response.receivedBy());
        assertNotNull(response.receivedAt());

        // Verify stock item updated from 10 to 60 (10 + 50)
        ArgumentCaptor<StockItem> stockCaptor = ArgumentCaptor.forClass(StockItem.class);
        verify(stockItemRepository).save(stockCaptor.capture());
        assertEquals(60, stockCaptor.getValue().getOnHand());

        // Verify batch created with 50 units
        ArgumentCaptor<StockBatch> batchCaptor = ArgumentCaptor.forClass(StockBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        assertEquals(50, batchCaptor.getValue().getQuantity());
        assertEquals("BATCH-MAGGI-01", batchCaptor.getValue().getBatchNumber());

        // Verify outbox event emitted
        verify(outboxService).publish(
            eq("INBOUND_ASN"),
            eq(asnId.toString()),
            eq("InboundAsnReconciled"),
            anyMap()
        );
    }

    @Test
    @DisplayName("Pallet receiving acquires PESSIMISTIC_WRITE StockItem locks sorted by productId")
    void shouldAcquireStockItemLocksSortedByProductId() {
        UUID storeId = UUID.randomUUID();
        UUID smallerProductId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largerProductId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        InboundAsn asn = new InboundAsn();
        asn.setId(UUID.randomUUID());
        asn.setAsnNumber("ASN-SORT-1");
        asn.setPurchaseOrderId("PO-SORT-1");
        asn.setVendorId("V1");
        asn.setStoreId(storeId);
        asn.setPalletBarcode("PALLET-SORT");
        asn.setStatus(AsnStatus.PENDING);

        AsnLineItem secondItem = new AsnLineItem();
        secondItem.setProductId(largerProductId);
        secondItem.setExpectedQuantity(5);
        asn.addLineItem(secondItem);

        AsnLineItem firstItem = new AsnLineItem();
        firstItem.setProductId(smallerProductId);
        firstItem.setExpectedQuantity(10);
        asn.addLineItem(firstItem);

        when(asnRepository.findByPalletBarcode("PALLET-SORT")).thenReturn(Optional.of(asn));
        when(asnRepository.save(any(InboundAsn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockItemRepository.findByProductIdAndStoreIdForUpdate(any(), eq(storeId)))
            .thenReturn(Optional.empty());
        when(stockItemRepository.save(any(StockItem.class))).thenAnswer(inv -> inv.getArgument(0));

        receivingService.receiveByPalletBarcode("PALLET-SORT", "OP-1");

        var lockOrder = inOrder(stockItemRepository);
        lockOrder.verify(stockItemRepository).findByProductIdAndStoreIdForUpdate(smallerProductId, storeId);
        lockOrder.verify(stockItemRepository).findByProductIdAndStoreIdForUpdate(largerProductId, storeId);
    }
}
