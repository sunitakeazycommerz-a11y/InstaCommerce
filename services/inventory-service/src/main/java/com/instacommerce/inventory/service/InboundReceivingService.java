package com.instacommerce.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboundReceivingService {
    private static final Logger log = LoggerFactory.getLogger(InboundReceivingService.class);

    private final InboundAsnRepository asnRepository;
    private final StockItemRepository stockItemRepository;
    private final StockBatchRepository batchRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public InboundReceivingService(InboundAsnRepository asnRepository,
                                   StockItemRepository stockItemRepository,
                                   StockBatchRepository batchRepository,
                                   OutboxService outboxService,
                                   ObjectMapper objectMapper) {
        this.asnRepository = asnRepository;
        this.stockItemRepository = stockItemRepository;
        this.batchRepository = batchRepository;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AsnResponse ingestAsn(AsnIngestRequest request) {
        if (asnRepository.findByAsnNumber(request.asnNumber()).isPresent()) {
            throw new IllegalArgumentException("ASN already exists with number: " + request.asnNumber());
        }
        if (asnRepository.findByPalletBarcode(request.palletBarcode()).isPresent()) {
            throw new IllegalArgumentException("Pallet barcode already registered: " + request.palletBarcode());
        }

        InboundAsn asn = new InboundAsn();
        asn.setAsnNumber(request.asnNumber());
        asn.setPurchaseOrderId(request.purchaseOrderId());
        asn.setVendorId(request.vendorId());
        asn.setStoreId(request.storeId());
        asn.setPalletBarcode(request.palletBarcode());
        asn.setStatus(AsnStatus.PENDING);

        try {
            asn.setRawPayload(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ASN raw payload: {}", e.getMessage());
        }

        for (AsnIngestRequest.AsnItemRequest itemReq : request.items()) {
            AsnLineItem item = new AsnLineItem();
            item.setProductId(itemReq.productId());
            item.setExpectedQuantity(itemReq.expectedQuantity());
            item.setReceivedQuantity(0);
            item.setBatchNumber(itemReq.batchNumber());
            item.setExpiryDate(itemReq.expiryDate());
            asn.addLineItem(item);
        }

        asn = asnRepository.save(asn);
        log.info("Ingested supplier ASN: asnNumber={} poId={} storeId={} palletBarcode={}",
            asn.getAsnNumber(), asn.getPurchaseOrderId(), asn.getStoreId(), asn.getPalletBarcode());

        return toResponse(asn);
    }

    @Transactional
    public AsnResponse receiveByPalletBarcode(String palletBarcode, String receivedBy) {
        InboundAsn asn = asnRepository.findByPalletBarcode(palletBarcode)
            .orElseThrow(() -> new NoSuchElementException("Inbound ASN not found for pallet barcode: " + palletBarcode));

        if (asn.getStatus() == AsnStatus.RECEIVED || asn.getStatus() == AsnStatus.RECONCILED) {
            throw new IllegalStateException("Pallet already received and reconciled: " + palletBarcode);
        }

        Instant now = Instant.now();
        asn.setReceivedAt(now);
        asn.setReceivedBy(receivedBy != null ? receivedBy : "SYSTEM_SCAN");
        asn.setStatus(AsnStatus.RECONCILED);

        final UUID storeId = asn.getStoreId();

        // Reconcile and update available inventory
        for (AsnLineItem item : asn.getLineItems()) {
            item.setReceivedQuantity(item.getExpectedQuantity());

            // 1. Update on-hand inventory in store
            StockItem stockItem = stockItemRepository.findByStoreIdAndProductId(storeId, item.getProductId())
                .orElseGet(() -> {
                    StockItem newStock = new StockItem();
                    newStock.setStoreId(storeId);
                    newStock.setProductId(item.getProductId());
                    newStock.setOnHand(0);
                    newStock.setReserved(0);
                    return newStock;
                });

            stockItem.setOnHand(stockItem.getOnHand() + item.getExpectedQuantity());
            stockItemRepository.save(stockItem);

            // 2. Register stock batch if batch number & expiry are supplied
            if (item.getBatchNumber() != null && !item.getBatchNumber().isBlank()) {
                StockBatch batch = batchRepository.findByStoreIdAndProductIdAndBatchNumber(
                    storeId, item.getProductId(), item.getBatchNumber())
                    .orElseGet(() -> {
                        StockBatch newBatch = new StockBatch();
                        newBatch.setStoreId(storeId);
                        newBatch.setProductId(item.getProductId());
                        newBatch.setBatchNumber(item.getBatchNumber());
                        newBatch.setExpiryDate(item.getExpiryDate() != null ? item.getExpiryDate() : now.plusSeconds(86400 * 30));
                        newBatch.setQuantity(0);
                        return newBatch;
                    });

                batch.setQuantity(batch.getQuantity() + item.getExpectedQuantity());
                if (item.getExpiryDate() != null) {
                    batch.setExpiryDate(item.getExpiryDate());
                }
                batchRepository.save(batch);
            }
        }

        asn = asnRepository.save(asn);

        // 3. Emit outbox event for inventory reconciliation and PO close
        Map<String, Object> payload = new HashMap<>();
        payload.put("asn_number", asn.getAsnNumber());
        payload.put("purchase_order_id", asn.getPurchaseOrderId());
        payload.put("vendor_id", asn.getVendorId());
        payload.put("store_id", asn.getStoreId().toString());
        payload.put("pallet_barcode", asn.getPalletBarcode());
        payload.put("status", asn.getStatus().name());
        payload.put("line_items_count", asn.getLineItems().size());
        payload.put("received_by", asn.getReceivedBy());
        payload.put("received_at", now.toString());

        outboxService.publish("INBOUND_ASN", asn.getId().toString(), "InboundAsnReconciled", payload);

        log.info("One-click pallet barcode receipt reconciled: palletBarcode={} asnNumber={} storeId={}",
            palletBarcode, asn.getAsnNumber(), asn.getStoreId());

        return toResponse(asn);
    }

    @Transactional(readOnly = true)
    public AsnResponse getByPalletBarcode(String palletBarcode) {
        return asnRepository.findByPalletBarcode(palletBarcode)
            .map(this::toResponse)
            .orElseThrow(() -> new NoSuchElementException("Inbound ASN not found for pallet barcode: " + palletBarcode));
    }

    @Transactional(readOnly = true)
    public AsnResponse getByAsnNumber(String asnNumber) {
        return asnRepository.findByAsnNumber(asnNumber)
            .map(this::toResponse)
            .orElseThrow(() -> new NoSuchElementException("Inbound ASN not found for ASN number: " + asnNumber));
    }

    @Transactional(readOnly = true)
    public List<AsnResponse> getByStoreId(UUID storeId) {
        return asnRepository.findByStoreId(storeId).stream().map(this::toResponse).toList();
    }

    private AsnResponse toResponse(InboundAsn asn) {
        List<AsnResponse.AsnLineItemResponse> lineItems = asn.getLineItems().stream()
            .map(item -> new AsnResponse.AsnLineItemResponse(
                item.getId(),
                item.getProductId(),
                item.getExpectedQuantity(),
                item.getReceivedQuantity(),
                item.getBatchNumber(),
                item.getExpiryDate()
            ))
            .toList();

        return new AsnResponse(
            asn.getId(),
            asn.getAsnNumber(),
            asn.getPurchaseOrderId(),
            asn.getVendorId(),
            asn.getStoreId(),
            asn.getPalletBarcode(),
            asn.getStatus(),
            asn.getReceivedAt(),
            asn.getReceivedBy(),
            asn.getCreatedAt(),
            lineItems
        );
    }
}
