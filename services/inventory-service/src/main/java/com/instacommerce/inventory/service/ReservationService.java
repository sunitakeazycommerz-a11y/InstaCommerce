package com.instacommerce.inventory.service;

import com.instacommerce.inventory.client.WarehouseClient;
import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.config.ReservationProperties;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.ReservationReleaseLog;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.mapper.InventoryMapper;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.PartialReleaseResponse;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.dto.response.ReservedItemResponse;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.exception.ReservationExpiredException;
import com.instacommerce.inventory.exception.ReservationNotFoundException;
import com.instacommerce.inventory.exception.ReservationStateException;
import com.instacommerce.inventory.exception.StockFrozenException;
import com.instacommerce.inventory.exception.StoreInactiveException;
import com.instacommerce.inventory.repository.ReservationReleaseLogRepository;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private final ReservationRepository reservationRepository;
    private final StockItemRepository stockItemRepository;
    private final ReservationProperties reservationProperties;
    private final EntityManager entityManager;
    private final OutboxService outboxService;
    private final InventoryProperties inventoryProperties;
    private final ObjectProvider<WarehouseClient> warehouseClientProvider;
    private final TransactionTemplate reserveTransactionTemplate;
    private final FefoBatchAllocationService fefoBatchAllocationService;
    private final StockLevelMonitor stockLevelMonitor;
    private final ReservationReleaseLogRepository releaseLogRepository;

    public ReservationService(ReservationRepository reservationRepository,
                              StockItemRepository stockItemRepository,
                              ReservationProperties reservationProperties,
                              EntityManager entityManager,
                              OutboxService outboxService,
                              InventoryProperties inventoryProperties,
                              ObjectProvider<WarehouseClient> warehouseClientProvider,
                              @Nullable PlatformTransactionManager transactionManager,
                              @Nullable FefoBatchAllocationService fefoBatchAllocationService,
                              @Nullable StockLevelMonitor stockLevelMonitor,
                              @Nullable ReservationReleaseLogRepository releaseLogRepository) {
        this.reservationRepository = reservationRepository;
        this.stockItemRepository = stockItemRepository;
        this.reservationProperties = reservationProperties;
        this.entityManager = entityManager;
        this.outboxService = outboxService;
        this.inventoryProperties = inventoryProperties;
        this.warehouseClientProvider = warehouseClientProvider;
        this.fefoBatchAllocationService = fefoBatchAllocationService;
        this.stockLevelMonitor = stockLevelMonitor != null
            ? stockLevelMonitor
            : new StockLevelMonitor(inventoryProperties, outboxService);
        this.releaseLogRepository = releaseLogRepository;
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            this.reserveTransactionTemplate = template;
        } else {
            this.reserveTransactionTemplate = null;
        }
    }

    public ReserveResponse reserve(ReserveRequest request) {
        validateStoreOperational(request.storeId());
        return reserveTransactionTemplate.execute(status -> reserveInTransaction(request));
    }

    private void validateStoreOperational(UUID storeId) {
        warehouseClientProvider.ifAvailable(client -> {
            client.validateStoreActive(storeId);
            if (!client.isStoreOpen(storeId)) {
                throw new StoreInactiveException(storeId, "CLOSED");
            }
        });
    }

    private ReserveResponse reserveInTransaction(ReserveRequest request) {
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
            if (stock.isFrozen()) {
                throw new StockFrozenException(item.productId(), request.storeId());
            }
            int available = fefoBatchAllocationService != null
                ? fefoBatchAllocationService.resolveSellableAvailable(stock)
                : Math.max(0, stock.getOnHand() - stock.getReserved());
            if (available < item.quantity()) {
                throw new InsufficientStockException(item.productId(), available, item.quantity());
            }
            lockedStock.put(item.productId(), stock);
        }
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(reservationProperties.getTtlMinutes()));
        Reservation reservation = ReservationSupport.createPendingReservation(
            request.idempotencyKey(), request.storeId(), expiresAt, sortedItems, lockedStock);
        for (InventoryItemRequest item : sortedItems) {
            stockItemRepository.save(lockedStock.get(item.productId()));
        }
        reservationRepository.save(reservation);

        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReserved", ReservationSupport.buildStockReservedPayload(
                reservation, request.idempotencyKey(), sortedItems, Instant.now(), expiresAt));

        for (InventoryItemRequest item : sortedItems) {
            checkLowStock(lockedStock.get(item.productId()), request.storeId());
        }

        return InventoryMapper.toReserveResponse(reservation);
    }

    @Transactional(noRollbackFor = ReservationExpiredException.class)
    public void confirm(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation {} is already confirmed; returning idempotently", reservationId);
            return;
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "confirm");
        }
        if (reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            throw new ReservationExpiredException(reservationId);
        }
        List<ReservationLineItem> items = ReservationSupport.sortedLineItems(reservation);
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (ReservationLineItem item : items) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            List<FefoBatchAllocationService.BatchAllocation> allocations = fefoBatchAllocationService != null
                ? fefoBatchAllocationService.allocateFefoForConfirm(
                    reservation.getStoreId(), item.getProductId(), item.getQuantity())
                : List.of();
            stock.confirm(item.getQuantity());
            stockItemRepository.save(stock);
            checkLowStock(stock, reservation.getStoreId());
            itemsList.add(ReservationSupport.buildConfirmedItemPayload(item, stock, allocations));
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        outboxService.publish("Reservation", reservationId.toString(),
            "StockConfirmed", ReservationSupport.buildStockConfirmedPayload(reservationId, reservation, itemsList));
    }

    @Transactional(noRollbackFor = ReservationExpiredException.class)
    public PartialReleaseResponse releasePartial(UUID reservationId, List<InventoryItemRequest> items, String releaseKey) {
        ReservationSupport.validatePartialReleaseRequest(items);

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        String normalizedKey = (releaseKey != null && !releaseKey.isBlank()) ? releaseKey.trim() : null;
        if (normalizedKey != null && releaseLogRepository != null
            && releaseLogRepository.existsByReservationIdAndReleaseKey(reservationId, normalizedKey)) {
            return new PartialReleaseResponse(
                reservation.getId(), reservation.getStatus(), List.of(),
                ReservationSupport.toReservedItemResponses(reservation));
        }

        if (reservation.getStatus() == ReservationStatus.PENDING
            && reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            throw new ReservationExpiredException(reservationId);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "partial-release");
        }

        Map<UUID, ReservationLineItem> lineByProduct = ReservationSupport.indexLinesByProduct(reservation);
        List<InventoryItemRequest> sortedItems = items.stream()
            .sorted(Comparator.comparing(InventoryItemRequest::productId))
            .toList();
        ReservationSupport.validatePartialReleaseAgainstLines(reservationId, sortedItems, lineByProduct);

        List<ReservedItemResponse> releasedItems = new ArrayList<>();
        for (InventoryItemRequest reqItem : sortedItems) {
            StockItem stock = lockStockItem(reqItem.productId(), reservation.getStoreId());
            stock.releaseReserved(reqItem.quantity());
            stockItemRepository.save(stock);

            ReservationLineItem line = lineByProduct.get(reqItem.productId());
            line.setQuantity(line.getQuantity() - reqItem.quantity());
            releasedItems.add(new ReservedItemResponse(reqItem.productId(), reqItem.quantity()));
        }

        boolean fullyCancelled = ReservationSupport.pruneZeroQuantityLines(reservation);
        if (fullyCancelled) {
            reservation.setStatus(ReservationStatus.CANCELLED);
        }
        reservationRepository.save(reservation);

        if (normalizedKey != null && releaseLogRepository != null) {
            ReservationReleaseLog releaseLog = new ReservationReleaseLog();
            releaseLog.setReservationId(reservationId);
            releaseLog.setReleaseKey(normalizedKey);
            releaseLogRepository.save(releaseLog);
        }

        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReleased", ReservationSupport.buildPartialReleasePayload(reservation, releasedItems, fullyCancelled));

        return new PartialReleaseResponse(
            reservation.getId(), reservation.getStatus(), releasedItems,
            ReservationSupport.toReservedItemResponses(reservation));
    }

    @Transactional(noRollbackFor = ReservationExpiredException.class)
    public void cancel(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("Reservation {} is already cancelled; returning idempotently", reservationId);
            return;
        }
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Releasing confirmed reservation {} back to on-hand inventory", reservationId);
            releaseConfirmed(reservation);
            reservation.setStatus(ReservationStatus.CANCELLED);
            reservationRepository.save(reservation);
            publishStockReleasedEvent(reservation);
            return;
        }
        if (reservation.getStatus() == ReservationStatus.PENDING
            && reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            throw new ReservationExpiredException(reservationId);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "cancel");
        }
        releaseReserved(reservation);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
        publishStockReleasedEvent(reservation);
    }

    @Transactional
    public void expireReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
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
        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReleased", ReservationSupport.buildStockReleasedPayload(reservation));
    }

    private void releaseReserved(Reservation reservation) {
        for (ReservationLineItem item : ReservationSupport.sortedLineItems(reservation)) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            stock.releaseReserved(item.getQuantity());
            stockItemRepository.save(stock);
        }
    }

    private void releaseConfirmed(Reservation reservation) {
        for (ReservationLineItem item : ReservationSupport.sortedLineItems(reservation)) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            stock.releaseConfirmed(item.getQuantity());
            stockItemRepository.save(stock);
            if (fefoBatchAllocationService != null) {
                fefoBatchAllocationService.restoreOnConfirmedCancel(
                    reservation.getStoreId(), item.getProductId(), item.getQuantity());
            }
        }
    }

    private void checkLowStock(StockItem stock, UUID storeId) {
        stockLevelMonitor.evaluateAfterStockDecrease(stock, storeId);
    }

    private StockItem lockStockItem(UUID productId, UUID storeId) {
        return ReservationSupport.lockStockItem(
            entityManager, productId, storeId, inventoryProperties.getLockTimeoutMs());
    }
}
