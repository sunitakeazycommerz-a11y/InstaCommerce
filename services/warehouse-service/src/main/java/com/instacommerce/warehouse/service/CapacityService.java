package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.exception.CapacityExceededException;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.DateTimeException;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityService {

    private static final Logger log = LoggerFactory.getLogger(CapacityService.class);
    private final StoreCapacityRepository capacityRepository;
    private final StoreRepository storeRepository;
    private final OutboxService outboxService;

    public CapacityService(StoreCapacityRepository capacityRepository,
                           StoreRepository storeRepository,
                           OutboxService outboxService) {
        this.capacityRepository = capacityRepository;
        this.storeRepository = storeRepository;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public CapacityResponse getStoreCapacity(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        ZonedDateTime storeNow = toStoreTime(store, now);
        LocalDate date = storeNow.toLocalDate();
        int hour = storeNow.getHour();
        Optional<StoreCapacity> capacity = capacityRepository
                .findByStoreIdAndDateAndHour(storeId, date, hour);

        int currentOrders = capacity.map(StoreCapacity::getCurrentOrders).orElse(0);
        int maxOrders = capacity.map(StoreCapacity::getMaxOrders).orElse(store.getCapacityOrdersPerHour());
        boolean hourlyCanAccept = currentOrders < maxOrders;

        int maxConcurrent = store.getMaxConcurrentOrders() > 0 ? store.getMaxConcurrentOrders() : 100;
        int currentPending = store.getCurrentPendingOrders();

        String throttleStatus;
        int etaExtensionMinutes;
        boolean canAcceptOrders;

        if (currentPending >= maxConcurrent) {
            throttleStatus = "PAUSED";
            etaExtensionMinutes = 15;
            canAcceptOrders = false;
        } else if (currentPending >= (int) Math.ceil(maxConcurrent * 0.9)) {
            throttleStatus = "THROTTLED";
            etaExtensionMinutes = 15;
            canAcceptOrders = hourlyCanAccept;
        } else {
            throttleStatus = "NORMAL";
            etaExtensionMinutes = 0;
            canAcceptOrders = hourlyCanAccept;
        }

        return new CapacityResponse(
                storeId,
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

    @Transactional
    public boolean canAcceptOrder(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));

        int maxConcurrent = store.getMaxConcurrentOrders() > 0 ? store.getMaxConcurrentOrders() : 100;
        if (store.getCurrentPendingOrders() >= maxConcurrent) {
            return false;
        }

        ZonedDateTime storeNow = toStoreTime(store, now);
        LocalDate date = storeNow.toLocalDate();
        int hour = storeNow.getHour();
        Optional<StoreCapacity> capacity = capacityRepository
                .findByStoreIdAndDateAndHour(storeId, date, hour);
        if (capacity.isEmpty()) {
            return true;
        }
        StoreCapacity cap = capacity.get();
        return cap.getCurrentOrders() < cap.getMaxOrders();
    }

    @Transactional
    public boolean acquireOrderSlot(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));

        int maxConcurrent = store.getMaxConcurrentOrders() > 0 ? store.getMaxConcurrentOrders() : 100;
        int currentPending = store.getCurrentPendingOrders();

        if (currentPending >= maxConcurrent) {
            log.warn("Dark store {} reached 100% capacity ({}/{}). Pausing new orders for micro-zone.",
                    storeId, currentPending, maxConcurrent);
            outboxService.publish("StoreCapacity", storeId.toString(), "StoreCapacityPaused",
                    Map.of("storeId", storeId.toString(), "currentPending", currentPending, "maxConcurrent", maxConcurrent));
            throw new CapacityExceededException(storeId.toString());
        }

        if (currentPending + 1 >= (int) Math.ceil(maxConcurrent * 0.9)) {
            log.warn("Dark store {} reached 90% capacity ({}/{}). Signaling checkout to extend ETA by 15 mins.",
                    storeId, currentPending + 1, maxConcurrent);
            outboxService.publish("StoreCapacity", storeId.toString(), "StoreCapacityThrottled",
                    Map.of("storeId", storeId.toString(), "currentPending", currentPending + 1,
                            "maxConcurrent", maxConcurrent, "etaExtensionMinutes", 15));
        }

        store.setCurrentPendingOrders(currentPending + 1);
        storeRepository.save(store);
        incrementOrderCount(storeId, now);
        return true;
    }

    @Transactional
    public void releaseOrderSlot(UUID storeId) {
        Store store = storeRepository.findById(storeId)
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
        ZonedDateTime storeNow = toStoreTime(store, now);
        LocalDate date = storeNow.toLocalDate();
        int hour = storeNow.getHour();
        int updated = capacityRepository.incrementOrderCount(
                storeId, date, hour, store.getCapacityOrdersPerHour());
        return updated > 0;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "capacityCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanupOldCapacityData() {
        LocalDate cutoff = LocalDate.now().minusDays(7);
        int deleted = capacityRepository.deleteOlderThan(cutoff);
        log.info("Capacity cleanup: deleted {} records older than {}", deleted, cutoff);
    }

    private ZonedDateTime toStoreTime(Store store, LocalDateTime now) {
        ZoneId storeZone = resolveZoneId(store.getTimezone());
        return now.atZone(ZoneId.systemDefault()).withZoneSameInstant(storeZone);
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
