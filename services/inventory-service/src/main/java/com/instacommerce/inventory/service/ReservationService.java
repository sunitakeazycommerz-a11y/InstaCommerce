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
import com.instacommerce.inventory.exception.InvalidStockAdjustmentException;
import com.instacommerce.inventory.exception.ProductNotFoundException;
import com.instacommerce.inventory.exception.ReservationExpiredException;
import com.instacommerce.inventory.exception.ReservationNotFoundException;
import com.instacommerce.inventory.exception.ReservationStateException;
import com.instacommerce.inventory.exception.StockFrozenException;
import com.instacommerce.inventory.exception.StoreInactiveException;
import com.instacommerce.inventory.repository.ReservationReleaseLogRepository;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
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
                              PlatformTransactionManager transactionManager) {
        this(
            reservationRepository,
            stockItemRepository,
            reservationProperties,
            entityManager,
            outboxService,
            inventoryProperties,
            warehouseClientProvider,
            transactionManager,
            null,
            new StockLevelMonitor(inventoryProperties, outboxService),
            null
        );
    }

    @Autowired
    public ReservationService(ReservationRepository reservationRepository,
                              StockItemRepository stockItemRepository,
                              ReservationProperties reservationProperties,
                              EntityManager entityManager,
                              OutboxService outboxService,
                              InventoryProperties inventoryProperties,
                              ObjectProvider<WarehouseClient> warehouseClientProvider,
                              PlatformTransactionManager transactionManager,
                              ObjectProvider<FefoBatchAllocationService> fefoAllocationProvider,
                              ObjectProvider<StockLevelMonitor> stockLevelMonitorProvider,
                              ObjectProvider<ReservationReleaseLogRepository> releaseLogRepositoryProvider) {
        this(
            reservationRepository,
            stockItemRepository,
            reservationProperties,
            entityManager,
            outboxService,
            inventoryProperties,
            warehouseClientProvider,
            transactionManager,
            fefoAllocationProvider != null ? fefoAllocationProvider.getIfAvailable() : null,
            stockLevelMonitorProvider != null
                ? stockLevelMonitorProvider.getIfAvailable(() -> new StockLevelMonitor(inventoryProperties, outboxService))
                : new StockLevelMonitor(inventoryProperties, outboxService),
            releaseLogRepositoryProvider != null ? releaseLogRepositoryProvider.getIfAvailable() : null
        );
    }

    public ReservationService(ReservationRepository reservationRepository,
                              StockItemRepository stockItemRepository,
                              ReservationProperties reservationProperties,
                              EntityManager entityManager,
                              OutboxService outboxService,
                              InventoryProperties inventoryProperties,
                              ObjectProvider<WarehouseClient> warehouseClientProvider,
                              PlatformTransactionManager transactionManager,
                              FefoBatchAllocationService fefoBatchAllocationService,
                              StockLevelMonitor stockLevelMonitor,
                              ReservationReleaseLogRepository releaseLogRepository) {
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
        Reservation reservation = new Reservation();
        reservation.setIdempotencyKey(request.idempotencyKey());
        reservation.setStoreId(request.storeId());
        reservation.setStatus(ReservationStatus.PENDING);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(reservationProperties.getTtlMinutes()));
        reservation.setExpiresAt(expiresAt);

        List<ReservationLineItem> lineItems = new ArrayList<>();
        for (InventoryItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            stock.reserve(item.quantity());
            stockItemRepository.save(stock);

            ReservationLineItem lineItem = new ReservationLineItem();
            lineItem.setReservation(reservation);
            lineItem.setProductId(item.productId());
            lineItem.setQuantity(item.quantity());
            lineItems.add(lineItem);
        }
        reservation.setLineItems(lineItems);
        reservationRepository.save(reservation);

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

        for (InventoryItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            checkLowStock(stock, request.storeId());
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
        List<ReservationLineItem> items = reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
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
            itemsList.add(m);
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reservationId", reservationId.toString());
        eventPayload.put("orderId", reservation.getIdempotencyKey());
        eventPayload.put("storeId", reservation.getStoreId().toString());
        eventPayload.put("items", itemsList);
        eventPayload.put("confirmedAt", Instant.now().toString());
        outboxService.publish("Reservation", reservationId.toString(),
            "StockConfirmed", eventPayload);
    }

    @Transactional(noRollbackFor = ReservationExpiredException.class)
    public PartialReleaseResponse releasePartial(UUID reservationId, List<InventoryItemRequest> items, String releaseKey) {
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

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        String normalizedKey = (releaseKey != null && !releaseKey.isBlank()) ? releaseKey.trim() : null;
        if (normalizedKey != null && releaseLogRepository != null
            && releaseLogRepository.existsByReservationIdAndReleaseKey(reservationId, normalizedKey)) {
            List<ReservedItemResponse> remaining = reservation.getLineItems().stream()
                .map(li -> new ReservedItemResponse(li.getProductId(), li.getQuantity()))
                .toList();
            return new PartialReleaseResponse(reservation.getId(), reservation.getStatus(), List.of(), remaining);
        }

        if (reservation.getStatus() == ReservationStatus.PENDING
            && reservation.getExpiresAt().isBefore(Instant.now())) {
            expireReservation(reservation);
            throw new ReservationExpiredException(reservationId);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ReservationStateException(reservationId, reservation.getStatus(), "partial-release");
        }

        Map<UUID, ReservationLineItem> lineByProduct = new HashMap<>();
        for (ReservationLineItem li : reservation.getLineItems()) {
            lineByProduct.put(li.getProductId(), li);
        }

        List<InventoryItemRequest> sortedItems = items.stream()
            .sorted(Comparator.comparing(InventoryItemRequest::productId))
            .toList();

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

        List<ReservedItemResponse> releasedItems = new ArrayList<>();
        for (InventoryItemRequest reqItem : sortedItems) {
            StockItem stock = lockStockItem(reqItem.productId(), reservation.getStoreId());
            stock.releaseReserved(reqItem.quantity());
            stockItemRepository.save(stock);

            ReservationLineItem line = lineByProduct.get(reqItem.productId());
            int remainingQty = line.getQuantity() - reqItem.quantity();
            line.setQuantity(remainingQty);
            releasedItems.add(new ReservedItemResponse(reqItem.productId(), reqItem.quantity()));
        }

        Iterator<ReservationLineItem> iterator = reservation.getLineItems().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getQuantity() <= 0) {
                iterator.remove();
            }
        }

        boolean fullyCancelled = reservation.getLineItems().isEmpty();
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
        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReleased", eventPayload);

        List<ReservedItemResponse> remainingItems = reservation.getLineItems().stream()
            .map(li -> new ReservedItemResponse(li.getProductId(), li.getQuantity()))
            .toList();
        return new PartialReleaseResponse(reservation.getId(), reservation.getStatus(), releasedItems, remainingItems);
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
        outboxService.publish("Reservation", reservation.getId().toString(),
            "StockReleased", eventPayload);
    }

    private void releaseReserved(Reservation reservation) {
        List<ReservationLineItem> items = reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
        for (ReservationLineItem item : items) {
            StockItem stock = lockStockItem(item.getProductId(), reservation.getStoreId());
            stock.releaseReserved(item.getQuantity());
            stockItemRepository.save(stock);
        }
    }

    private void releaseConfirmed(Reservation reservation) {
        List<ReservationLineItem> items = reservation.getLineItems().stream()
            .sorted(Comparator.comparing(ReservationLineItem::getProductId))
            .toList();
        for (ReservationLineItem item : items) {
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
        try {
            return entityManager.createQuery(
                    "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
                    StockItem.class)
                .setParameter("pid", productId)
                .setParameter("sid", storeId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(LOCK_TIMEOUT_HINT, inventoryProperties.getLockTimeoutMs())
                .getSingleResult();
        } catch (NoResultException ex) {
            throw new ProductNotFoundException(productId, storeId);
        }
    }
}
