package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.dto.mapper.StoreMapper;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    public CapacityService(StoreCapacityRepository capacityRepository,
                           StoreRepository storeRepository) {
        this.capacityRepository = capacityRepository;
        this.storeRepository = storeRepository;
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
        if (capacity.isPresent()) {
            return StoreMapper.toCapacityResponse(capacity.get());
        }
        return new CapacityResponse(storeId, date, hour, 0,
                store.getCapacityOrdersPerHour(), true);
    }

    @Transactional
    public boolean canAcceptOrder(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
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
