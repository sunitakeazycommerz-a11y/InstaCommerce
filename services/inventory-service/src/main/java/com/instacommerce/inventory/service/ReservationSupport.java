package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.response.ReservedItemResponse;
import com.instacommerce.inventory.exception.InvalidStockAdjustmentException;
import com.instacommerce.inventory.exception.ProductNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReservationSupport {
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";

    private ReservationSupport() {
    }

    static StockItem lockStockItem(EntityManager entityManager,
                                   UUID productId,
                                   UUID storeId,
                                   int lockTimeoutMs) {
        try {
            return entityManager.createQuery(
                    "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
                    StockItem.class)
                .setParameter("pid", productId)
                .setParameter("sid", storeId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(LOCK_TIMEOUT_HINT, lockTimeoutMs)
                .getSingleResult();
        } catch (NoResultException ex) {
            throw new ProductNotFoundException(productId, storeId);
        }
    }

    static void validatePartialReleaseRequest(List<InventoryItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidStockAdjustmentException("Partial release requires at least one item");
        }
        Set<UUID> seenProducts = new HashSet<>();
        for (InventoryItemRequest item : items) {
            if (item == null || item.productId() == null) {
                throw new InvalidStockAdjustmentException("ProductId is required for partial release");
            }
            if (item.quantity() <= 0) {
                throw new InvalidStockAdjustmentException("Release quantity must be strictly positive");
            }
            if (!seenProducts.add(item.productId())) {
                throw new InvalidStockAdjustmentException("Duplicate productId in partial release: " + item.productId());
            }
        }
    }

    static Map<UUID, ReservationLineItem> indexLinesByProduct(Reservation reservation) {
        Map<UUID, ReservationLineItem> lineByProduct = new HashMap<>();
        for (ReservationLineItem li : reservation.getLineItems()) {
            lineByProduct.put(li.getProductId(), li);
        }
        return lineByProduct;
    }

    static void validatePartialReleaseAgainstLines(UUID reservationId,
                                                   List<InventoryItemRequest> sortedItems,
                                                   Map<UUID, ReservationLineItem> lineByProduct) {
        for (InventoryItemRequest reqItem : sortedItems) {
            ReservationLineItem line = lineByProduct.get(reqItem.productId());
            if (line == null) {
                throw new InvalidStockAdjustmentException(
                    "Product " + reqItem.productId() + " is not part of reservation " + reservationId);
            }
            if (reqItem.quantity() > line.getQuantity()) {
                throw new InvalidStockAdjustmentException(
                    "Requested release quantity " + reqItem.quantity() + " exceeds reserved quantity "
                        + line.getQuantity() + " for product " + reqItem.productId());
            }
        }
    }

    static boolean pruneZeroQuantityLines(Reservation reservation) {
        Iterator<ReservationLineItem> iterator = reservation.getLineItems().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getQuantity() <= 0) {
                iterator.remove();
            }
        }
        return reservation.getLineItems().isEmpty();
    }

    static List<ReservedItemResponse> toReservedItemResponses(Reservation reservation) {
        return reservation.getLineItems().stream()
            .map(li -> new ReservedItemResponse(li.getProductId(), li.getQuantity()))
            .toList();
    }

    static List<ReservationLineItem> sortedLineItems(Reservation reservation) {
        return reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
    }

    static Map<String, Object> buildStockReservedPayload(Reservation reservation,
                                                         String idempotencyKey,
                                                         List<InventoryItemRequest> sortedItems,
                                                         Instant reservedAt,
                                                         Instant expiresAt) {
        List<Map<String, Object>> itemsList = sortedItems.stream()
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", item.productId().toString());
                m.put("quantity", item.quantity());
                return m;
            })
            .toList();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservation.getId().toString());
        eventPayload.put("orderId", idempotencyKey);
        eventPayload.put("items", itemsList);
        eventPayload.put("reservedAt", reservedAt.toString());
        eventPayload.put("expiresAt", expiresAt.toString());
        return eventPayload;
    }

    static Map<String, Object> buildConfirmedItemPayload(
            ReservationLineItem item,
            StockItem stock,
            List<FefoBatchAllocationService.BatchAllocation> allocations) {
        int available = stock.getOnHand() - stock.getReserved();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", item.getProductId().toString());
        m.put("quantity", item.getQuantity());
        m.put("remainingOnHand", stock.getOnHand());
        m.put("remainingAvailable", available);
        m.put("inStock", available > 0);
        if (!allocations.isEmpty()) {
            m.put("batches", allocations.stream()
                .map(a -> Map.of(
                    "batchNumber", a.batchNumber(),
                    "quantity", a.quantity(),
                    "expiryDate", a.expiryDate().toString()))
                .toList());
        }
        return m;
    }

    static Map<String, Object> buildStockConfirmedPayload(UUID reservationId,
                                                          Reservation reservation,
                                                          List<Map<String, Object>> itemsList) {
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservationId.toString());
        eventPayload.put("orderId", reservation.getIdempotencyKey());
        eventPayload.put("storeId", reservation.getStoreId().toString());
        eventPayload.put("items", itemsList);
        eventPayload.put("confirmedAt", Instant.now().toString());
        return eventPayload;
    }

    static Map<String, Object> buildPartialReleasePayload(Reservation reservation,
                                                          List<ReservedItemResponse> releasedItems,
                                                          boolean fullyCancelled) {
        List<Map<String, Object>> eventItems = releasedItems.stream()
            .map(ri -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", ri.productId().toString());
                m.put("quantity", ri.quantity());
                m.put("inStock", true);
                return m;
            })
            .toList();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservation.getId().toString());
        eventPayload.put("orderId", reservation.getIdempotencyKey());
        eventPayload.put("storeId", reservation.getStoreId().toString());
        eventPayload.put("items", eventItems);
        eventPayload.put("releasedAt", Instant.now().toString());
        eventPayload.put("reason", fullyCancelled ? "CANCELLED" : "PARTIAL_RELEASE");
        eventPayload.put("partial", !fullyCancelled);
        return eventPayload;
    }

    static Map<String, Object> buildStockReleasedPayload(Reservation reservation) {
        List<Map<String, Object>> itemsList = reservation.getLineItems().stream()
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", item.getProductId().toString());
                m.put("quantity", item.getQuantity());
                m.put("inStock", true);
                return m;
            })
            .toList();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservation.getId().toString());
        eventPayload.put("orderId", reservation.getIdempotencyKey());
        eventPayload.put("storeId", reservation.getStoreId().toString());
        eventPayload.put("items", itemsList);
        eventPayload.put("releasedAt", Instant.now().toString());
        eventPayload.put("reason", reservation.getStatus().name());
        return eventPayload;
    }

    static Reservation createPendingReservation(String idempotencyKey,
                                                UUID storeId,
                                                Instant expiresAt,
                                                List<InventoryItemRequest> sortedItems,
                                                Map<UUID, StockItem> lockedStock) {
        Reservation reservation = new Reservation();
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setStoreId(storeId);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setExpiresAt(expiresAt);
        List<ReservationLineItem> lineItems = new ArrayList<>();
        for (InventoryItemRequest item : sortedItems) {
            lockedStock.get(item.productId()).reserve(item.quantity());
            ReservationLineItem lineItem = new ReservationLineItem();
            lineItem.setReservation(reservation);
            lineItem.setProductId(item.productId());
            lineItem.setQuantity(item.quantity());
            lineItems.add(lineItem);
        }
        reservation.setLineItems(lineItems);
        return reservation;
    }
}
