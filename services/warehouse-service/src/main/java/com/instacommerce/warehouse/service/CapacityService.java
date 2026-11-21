package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.dto.request.SurgeModeRequest;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.exception.CapacityExceededException;
import com.instacommerce.warehouse.exception.StoreClosedException;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreBlackoutWindowRepository;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityService {

    private static final Logger log = LoggerFactory.getLogger(CapacityService.class);
    private final StoreCapacityRepository capacityRepository;
    private final StoreRepository storeRepository;
    private final OutboxService outboxService;
    private final CapacityAlertPublisher capacityAlertPublisher;
    private final StoreBlackoutWindowRepository blackoutWindowRepository;
    private final Clock clock;

    public CapacityService(StoreCapacityRepository capacityRepository,
                           StoreRepository storeRepository,
                           OutboxService outboxService,
                           CapacityAlertPublisher capacityAlertPublisher,
                           StoreBlackoutWindowRepository blackoutWindowRepository,
                           Clock clock) {
        this.capacityRepository = capacityRepository;
        this.storeRepository = storeRepository;
        this.outboxService = outboxService;
        this.capacityAlertPublisher = capacityAlertPublisher != null
                ? capacityAlertPublisher
                : new CapacityAlertPublisher(outboxService);
        this.blackoutWindowRepository = blackoutWindowRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public CapacityResponse getStoreCapacity(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        return buildCapacityResponse(store, now);
    }

    @Transactional(readOnly = true)
    public boolean canAcceptOrder(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        return buildCapacityResponse(store, now).canAcceptOrders();
    }

    @Transactional
    @CacheEvict(value = "stores", key = "#storeId")
    public CapacityResponse updateSurgeMode(UUID storeId, SurgeModeRequest request) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        store.setSurgeModeEnabled(Boolean.TRUE.equals(request.enabled()));
        if (request.etaExtensionMinutes() != null) {
            store.setSurgeEtaExtensionMinutes(request.etaExtensionMinutes());
        }
        if (request.thresholdPercent() != null) {
            store.setSurgeThresholdPercent(request.thresholdPercent());
        }
        Store saved = storeRepository.save(store);
        outboxService.publish("StoreCapacity", storeId.toString(), "StoreSurgeModeChanged",
                Map.of("storeId", storeId.toString(),
                        "surgeModeEnabled", saved.isSurgeModeEnabled(),
                        "etaExtensionMinutes", saved.getSurgeEtaExtensionMinutes(),
                        "thresholdPercent", saved.getSurgeThresholdPercent()));
        return buildCapacityResponse(saved, null);
    }

    @Transactional
    public boolean acquireOrderSlot(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        ZonedDateTime storeNow = toStoreTime(store, now);

        if (store.getStatus() != StoreStatus.ACTIVE) {
            throw new StoreClosedException(storeId.toString(), "status is " + store.getStatus().name());
        }
        if (isBlackedOut(storeId, storeNow.toInstant())) {
            throw new StoreClosedException(storeId.toString(), "active blackout window");
        }

        int maxConcurrent = resolveMaxConcurrent(store);
        int currentPending = store.getCurrentPendingOrders();
        int throttleThreshold = resolveThrottleThreshold(store, maxConcurrent);
        int etaExt = resolveEtaExtension(store);

        if (currentPending >= maxConcurrent) {
            log.warn("Dark store {} reached 100% capacity ({}/{}). Pausing new orders for micro-zone.",
                    storeId, currentPending, maxConcurrent);
            capacityAlertPublisher.publishCapacityPaused(storeId.toString(),
                    Map.of("storeId", storeId.toString(), "currentPending", currentPending, "maxConcurrent", maxConcurrent));
            throw new CapacityExceededException(storeId.toString());
        }

        if (store.isSurgeModeEnabled() || currentPending + 1 >= throttleThreshold) {
            log.warn("Dark store {} entering throttled/surge state ({}/{}, surge={}). Extending ETA by {} mins.",
                    storeId, currentPending + 1, maxConcurrent, store.isSurgeModeEnabled(), etaExt);
            outboxService.publish("StoreCapacity", storeId.toString(), "StoreCapacityThrottled",
                    Map.of("storeId", storeId.toString(), "currentPending", currentPending + 1,
                            "maxConcurrent", maxConcurrent, "surgeMode", store.isSurgeModeEnabled(),
                            "etaExtensionMinutes", etaExt));
        }

        store.setCurrentPendingOrders(currentPending + 1);
        storeRepository.save(store);
        if (!incrementHourlyOrderCount(store, storeNow)) {
            log.warn("Dark store {} reached hourly order capacity. Pausing new orders.", storeId);
            capacityAlertPublisher.publishCapacityPaused(storeId.toString(),
                    Map.of("storeId", storeId.toString(), "currentPending", currentPending,
                            "maxOrdersPerHour", store.getCapacityOrdersPerHour()));
            throw new CapacityExceededException(storeId.toString());
        }
        return true;
    }

    @Transactional
    public void releaseOrderSlot(UUID storeId) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        int current = store.getCurrentPendingOrders();
        if (current > 0) {
            store.setCurrentPendingOrders(current - 1);
            storeRepository.save(store);
        }
    }

    @Transactional
    public boolean incrementOrderCount(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        return incrementHourlyOrderCount(store, toStoreTime(store, now));
    }

    private boolean incrementHourlyOrderCount(Store store, ZonedDateTime storeNow) {
        LocalDate date = storeNow.toLocalDate();
        int hour = storeNow.getHour();
        int updated = capacityRepository.incrementOrderCount(
                store.getId(), date, hour, store.getCapacityOrdersPerHour());
        return updated > 0;
    }

    private CapacityResponse buildCapacityResponse(Store store, LocalDateTime now) {
        ZonedDateTime storeNow = toStoreTime(store, now);
        LocalDate date = storeNow.toLocalDate();
        int hour = storeNow.getHour();
        Optional<StoreCapacity> capacity = capacityRepository
                .findByStoreIdAndDateAndHour(store.getId(), date, hour);

        int currentOrders = capacity.map(StoreCapacity::getCurrentOrders).orElse(0);
        int maxOrders = capacity.map(StoreCapacity::getMaxOrders).orElse(store.getCapacityOrdersPerHour());
        boolean hourlyCanAccept = currentOrders < maxOrders;

        int maxConcurrent = resolveMaxConcurrent(store);
        int currentPending = store.getCurrentPendingOrders();
        int throttleThreshold = resolveThrottleThreshold(store, maxConcurrent);
        int etaExt = resolveEtaExtension(store);

        String throttleStatus;
        int etaExtensionMinutes;
        boolean canAcceptOrders;

        if (store.getStatus() != StoreStatus.ACTIVE || isBlackedOut(store.getId(), storeNow.toInstant())) {
            throttleStatus = "PAUSED";
            etaExtensionMinutes = etaExt;
            canAcceptOrders = false;
        } else if (currentPending >= maxConcurrent) {
            throttleStatus = "PAUSED";
            etaExtensionMinutes = etaExt;
            canAcceptOrders = false;
        } else if (store.isSurgeModeEnabled()) {
            throttleStatus = "SURGE";
            etaExtensionMinutes = etaExt;
            canAcceptOrders = hourlyCanAccept;
        } else if (currentPending >= throttleThreshold) {
            throttleStatus = "THROTTLED";
            etaExtensionMinutes = etaExt;
            canAcceptOrders = hourlyCanAccept;
        } else {
            throttleStatus = "NORMAL";
            etaExtensionMinutes = 0;
            canAcceptOrders = hourlyCanAccept;
        }

        return new CapacityResponse(
                store.getId(),
                date,
                hour,
                currentOrders,
                maxOrders,
                canAcceptOrders,
                maxConcurrent,
                currentPending,
                throttleStatus,
                etaExtensionMinutes
        );
    }

    private boolean isBlackedOut(UUID storeId, Instant at) {
        return blackoutWindowRepository != null && blackoutWindowRepository.existsActiveBlackout(storeId, at);
    }

    private int resolveMaxConcurrent(Store store) {
        return store.getMaxConcurrentOrders() > 0 ? store.getMaxConcurrentOrders() : 100;
    }

    private int resolveThrottleThreshold(Store store, int maxConcurrent) {
        int pct = store.getSurgeThresholdPercent() > 0 && store.getSurgeThresholdPercent() <= 100
                ? store.getSurgeThresholdPercent()
                : 90;
        return (int) Math.ceil(maxConcurrent * (pct / 100.0));
    }

    private int resolveEtaExtension(Store store) {
        return store.getSurgeEtaExtensionMinutes() > 0 ? store.getSurgeEtaExtensionMinutes() : 15;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "capacityCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanupOldCapacityData() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(7);
        int deleted = capacityRepository.deleteOlderThan(cutoff);
        log.info("Capacity cleanup: deleted {} records older than {}", deleted, cutoff);
    }

    private ZonedDateTime toStoreTime(Instant instant, Store store) {
        return ZonedDateTime.ofInstant(instant, resolveZoneId(store.getTimezone()));
    }

    private ZonedDateTime toStoreTime(Store store, LocalDateTime now) {
        if (now != null) {
            return now.atZone(ZoneId.systemDefault()).withZoneSameInstant(resolveZoneId(store.getTimezone()));
        }
        return toStoreTime(clock.instant(), store);
    }

    private ZoneId resolveZoneId(String timezone) {
        String value = (timezone == null || timezone.isBlank()) ? "UTC" : timezone.trim();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Invalid timezone");
        }
    }
}
