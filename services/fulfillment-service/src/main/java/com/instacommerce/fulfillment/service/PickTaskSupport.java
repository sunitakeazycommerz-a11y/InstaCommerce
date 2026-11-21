package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.client.StoreCoordinates;
import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.exception.DuplicateScanException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PickTaskSupport {

    private PickTaskSupport() {
    }

    static void validateItemUpdate(PickItem item, MarkItemPickedRequest request, int pickedQty) {
        if (request.status() == PickItemStatus.PENDING) {
            throw new IllegalArgumentException("Cannot reset pick item status to PENDING");
        }
        if (pickedQty < 0) {
            throw new IllegalArgumentException("Picked quantity cannot be negative");
        }
        if (pickedQty > item.getQuantity()) {
            throw new IllegalArgumentException("Picked quantity cannot exceed ordered quantity");
        }
        if (request.status() == PickItemStatus.PICKED && pickedQty == 0) {
            throw new IllegalArgumentException("PICKED status requires pickedQty > 0; use MISSING when unavailable");
        }
        if (request.status() == PickItemStatus.SUBSTITUTED) {
            if (request.substituteProductId() == null) {
                throw new IllegalArgumentException("Substitution requires substituteProductId");
            }
            if (request.substituteProductId().equals(item.getProductId())) {
                throw new IllegalArgumentException("Substitute product cannot match ordered product");
            }
            if (pickedQty == 0) {
                throw new IllegalArgumentException("SUBSTITUTED status requires positive substituted quantity");
            }
        }
        if (item.getStatus() != PickItemStatus.PENDING && item.getStatus() != request.status()) {
            throw new IllegalStateException("Pick item already resolved as " + item.getStatus()
                + " and cannot transition to " + request.status());
        }
    }

    static boolean isIdempotentRepeat(PickItem item, MarkItemPickedRequest request, int resolvedQty) {
        UUID requestedSub = request.status() == PickItemStatus.SUBSTITUTED ? request.substituteProductId() : null;
        return item.getStatus() != PickItemStatus.PENDING
            && item.getStatus() == request.status()
            && item.getPickedQty() == resolvedQty
            && Objects.equals(item.getSubstituteProductId(), requestedSub);
    }

    static void recordAndCheckDebounce(Map<String, Long> scanDebounceMap, UUID orderId, String barcode) {
        long now = System.currentTimeMillis();
        if (scanDebounceMap.size() > 1000) {
            scanDebounceMap.entrySet().removeIf(entry -> (now - entry.getValue()) >= 1000);
        }
        String debounceKey = orderId + ":" + barcode;
        Long lastScan = scanDebounceMap.get(debounceKey);
        if (lastScan != null && (now - lastScan) < 1000) {
            throw new DuplicateScanException(
                "Duplicate scan detected for barcode " + barcode + " within 1000ms debounce window");
        }
        scanDebounceMap.put(debounceKey, now);
    }

    static void applyItemUpdate(PickItem item, MarkItemPickedRequest request, int pickedQty) {
        item.setPickedQty(pickedQty);
        item.setStatus(request.status());
        item.setNote(request.note());
        item.setSubstituteProductId(
            request.status() == PickItemStatus.SUBSTITUTED ? request.substituteProductId() : null);
    }

    static int safeQty(MarkItemPickedRequest request, int defaultQty) {
        if (request.pickedQty() != null) {
            return request.pickedQty();
        }
        if (request.status() == PickItemStatus.PICKED || request.status() == PickItemStatus.SUBSTITUTED) {
            return defaultQty;
        }
        return 0;
    }

    static int calculateMissingQtyDelta(PickItemStatus previousStatus, int previousPickedQty, PickItem item) {
        int previousMissingQty = missingQtyForStatus(previousStatus, item.getQuantity(), previousPickedQty);
        int currentMissingQty = missingQtyForStatus(item.getStatus(), item.getQuantity(), item.getPickedQty());
        return Math.max(currentMissingQty - previousMissingQty, 0);
    }

    private static int missingQtyForStatus(PickItemStatus status, int quantity, int pickedQty) {
        if (status == PickItemStatus.MISSING) {
            return Math.max(quantity - pickedQty, 0);
        }
        if ((status == PickItemStatus.PICKED || status == PickItemStatus.SUBSTITUTED) && pickedQty < quantity) {
            return quantity - pickedQty;
        }
        return 0;
    }

    static PickItem toPickItem(PickTask task, OrderPlacedItem item) {
        PickItem pickItem = new PickItem();
        pickItem.setPickTask(task);
        pickItem.setProductId(item.productId());
        pickItem.setProductName(item.productName());
        pickItem.setSku(item.sku());
        pickItem.setQuantity(item.quantity());
        pickItem.setPickedQty(0);
        pickItem.setUnitPriceCents(item.unitPriceCents());
        pickItem.setLineTotalCents(item.lineTotalCents());
        pickItem.setStatus(PickItemStatus.PENDING);
        pickItem.setAisle(item.aisle());
        pickItem.setShelf(item.shelf());
        pickItem.setBin(item.bin());
        pickItem.setBarcode(item.barcode());
        pickItem.setTareWeightGrams(item.tareWeightGrams());
        pickItem.setColdChain(item.isColdChainRequired());
        pickItem.setFragile(item.isFragileRequired());
        pickItem.setStorageTemperature(item.storageTemperature());
        return pickItem;
    }

    static int calculateItemsWeightGrams(List<PickItem> items, boolean useOrderedQuantity) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int totalWeightGrams = 0;
        for (PickItem item : items) {
            Integer tareWeight = item.getTareWeightGrams();
            if (tareWeight == null || tareWeight <= 0) {
                continue;
            }
            int qty = useOrderedQuantity ? item.getQuantity() : effectivePackedQty(item);
            if (qty > 0) {
                totalWeightGrams += tareWeight * qty;
            }
        }
        return totalWeightGrams;
    }

    private static int effectivePackedQty(PickItem item) {
        if (item.getStatus() == PickItemStatus.MISSING) {
            return 0;
        }
        if (item.getStatus() == PickItemStatus.PICKED || item.getStatus() == PickItemStatus.SUBSTITUTED) {
            return Math.max(0, item.getPickedQty());
        }
        return Math.max(0, item.getQuantity());
    }

    static Map<String, Object> buildOrderPackedPayload(PickTask task,
                                                       String note,
                                                       int packedWeightGrams,
                                                       StoreCoordinates coords) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", task.getOrderId());
        payload.put("userId", task.getUserId());
        payload.put("storeId", task.getStoreId());
        payload.put("packedAt", task.getCompletedAt());
        payload.put("note", note == null ? "" : note);
        payload.put("orderWeightGrams", packedWeightGrams);
        payload.put("coldChain", task.isColdChainRequired());
        payload.put("fragile", task.isFragileRequired());
        payload.put("coldChainRequired", task.isColdChainRequired());
        payload.put("fragileRequired", task.isFragileRequired());
        if (coords != null) {
            payload.put("pickupLat", coords.latitude());
            payload.put("pickupLng", coords.longitude());
        }
        if (task.getDropoffLat() != null) {
            payload.put("dropoffLat", task.getDropoffLat());
        }
        if (task.getDropoffLng() != null) {
            payload.put("dropoffLng", task.getDropoffLng());
        }
        return payload;
    }
}
