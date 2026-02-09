package com.instacommerce.inventory.service;

import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.mapper.InventoryMapper;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.exception.ProductNotFoundException;
import com.instacommerce.inventory.exception.ReservationExpiredException;
import com.instacommerce.inventory.exception.ReservationNotFoundException;
import com.instacommerce.inventory.exception.ReservationStateException;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final StockItemRepository stockItemRepository;
    private final ReservationProperties reservationProperties;
    private final EntityManager entityManager;
    private final OutboxService outboxService;
    private final InventoryProperties inventoryProperties;

    public ReservationService(ReservationRepository reservationRepository,
                              StockItemRepository stockItemRepository,
                              ReservationProperties reservationProperties,
                              EntityManager entityManager,
                              OutboxService outboxService,
                              InventoryProperties inventoryProperties) {
        this.reservationRepository = reservationRepository;
        this.stockItemRepository = stockItemRepository;
        this.reservationProperties = reservationProperties;
        this.entityManager = entityManager;
        this.outboxService = outboxService;
        this.inventoryProperties = inventoryProperties;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReserveResponse reserve(ReserveRequest request) {
        Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return InventoryMapper.toReserveResponse(existing.get());
        }
        List<InventoryItemRequest> sortedItems = request.items().stream()
            .sorted(Comparator.comparing(InventoryItemRequest::productId))
            .toList();
        Map<UUID, StockItem> lockedStock = new LinkedHashMap<>();
        for (InventoryItemRequest item : sortedItems) {
            StockItem stock = lockStockItem(item.productId(), request.storeId());
            int available = stock.getOnHand() - stock.getReserved();
            if (available < item.quantity()) {
                throw new InsufficientStockException(item.productId(), available, item.quantity());
            }
            lockedStock.put(item.productId(), stock);
        }
        Reservation reservation = new Reservation();
        reservation.setIdempotencyKey(request.idempotencyKey());
        reservation.setStoreId(request.storeId());
        reservation.setStatus(ReservationStatus.PENDING);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(reservationProperties.getTtlMinutes()));
        reservation.setExpiresAt(expiresAt);

        List<ReservationLineItem> lineItems = new ArrayList<>();
        for (InventoryItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            stock.setReserved(stock.getReserved() + item.quantity());
            stockItemRepository.save(stock);

            ReservationLineItem lineItem = new ReservationLineItem();
            lineItem.setReservation(reservation);
            lineItem.setProductId(item.productId());
            lineItem.setQuantity(item.quantity());
            lineItems.add(lineItem);
        }
        reservation.setLineItems(lineItems);
        reservationRepository.save(reservation);

        // Publish StockReserved event
        Instant now = Instant.now();
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
        eventPayload.put("orderId", request.idempotencyKey());
        eventPayload.put("items", itemsList);
        eventPayload.put("reservedAt", now.toString());
        eventPayload.put("expiresAt", expiresAt.toString());
        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReserved", eventPayload);

        // Check low stock after reservation
        for (InventoryItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            checkLowStock(stock, request.storeId());
        }

        return InventoryMapper.toReserveResponse(reservation);
    }

    @Transactional
    public void confirm(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return;
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "confirm");
        }
        if (reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            throw new ReservationExpiredException(reservationId);
        }
        List<ReservationLineItem> items = reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
        for (ReservationLineItem item : items) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            stock.setOnHand(stock.getOnHand() - item.getQuantity());
            stock.setReserved(stock.getReserved() - item.getQuantity());
            stockItemRepository.save(stock);
            checkLowStock(stock, reservation.getStoreId());
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Publish StockConfirmed event
        List<Map<String, Object>> itemsList = items.stream()
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", item.getProductId().toString());
                m.put("quantity", item.getQuantity());
                return m;
            })
            .toList();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservationId.toString());
        eventPayload.put("items", itemsList);
        eventPayload.put("confirmedAt", Instant.now().toString());
        outboxService.publish("Reservation", reservationId.toString(),
            "StockConfirmed", eventPayload);
    }

    @Transactional
    public void cancel(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED
            || reservation.getStatus() == ReservationStatus.EXPIRED) {
            return;
        }
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "cancel");
        }
        if (reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            return;
        }
        releaseReserved(reservation);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // Publish StockReleased event
        publishStockReleasedEvent(reservation);
    }

    @Transactional
    public void expireReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            return;
        }
        expireReservation(reservation);
    }

    private void expireReservation(Reservation reservation) {
        releaseReserved(reservation);
        reservation.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.save(reservation);

        publishStockReleasedEvent(reservation);
    }

    private void publishStockReleasedEvent(Reservation reservation) {
        List<Map<String, Object>> itemsList = reservation.getLineItems().stream()
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", item.getProductId().toString());
                m.put("quantity", item.getQuantity());
                return m;
            })
            .toList();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservation.getId().toString());
        eventPayload.put("items", itemsList);
        eventPayload.put("releasedAt", Instant.now().toString());
        eventPayload.put("reason", reservation.getStatus().name());
        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReleased", eventPayload);
    }

    private void releaseReserved(Reservation reservation) {
        List<ReservationLineItem> items = reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
        for (ReservationLineItem item : items) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            stock.setReserved(stock.getReserved() - item.getQuantity());
            stockItemRepository.save(stock);
        }
    }

    private void checkLowStock(StockItem stock, String storeId) {
        int available = stock.getOnHand() - stock.getReserved();
        int threshold = inventoryProperties.getLowStockThreshold();
        if (available <= threshold) {
            Map<String, Object> alertPayload = new LinkedHashMap<>();
            alertPayload.put("productId", stock.getProductId().toString());
            alertPayload.put("warehouseId", storeId);
            alertPayload.put("currentQuantity", available);
            alertPayload.put("threshold", threshold);
            alertPayload.put("detectedAt", Instant.now().toString());
            outboxService.publish("StockItem", stock.getProductId().toString(),
                "LowStockAlert", alertPayload);
        }
    }

    private StockItem lockStockItem(UUID productId, String storeId) {
        try {
            return entityManager.createQuery(
                    "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
                    StockItem.class)
                .setParameter("pid", productId)
                .setParameter("sid", storeId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
        } catch (NoResultException ex) {
            throw new ProductNotFoundException(productId, storeId);
        }
    }
}
