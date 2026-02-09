package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.dto.mapper.StoreMapper;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        LocalDate date = now.toLocalDate();
        int hour = now.getHour();
        Optional<StoreCapacity> capacity = capacityRepository
                .findByStoreIdAndDateAndHour(storeId, date, hour);
        if (capacity.isPresent()) {
            return StoreMapper.toCapacityResponse(capacity.get());
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        return new CapacityResponse(storeId, date, hour, 0,
                store.getCapacityOrdersPerHour(), true);
    }

    @Transactional
    public boolean canAcceptOrder(UUID storeId, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        int hour = now.getHour();
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
        LocalDate date = now.toLocalDate();
        int hour = now.getHour();
        Optional<StoreCapacity> existing = capacityRepository
                .findByStoreIdAndDateAndHour(storeId, date, hour);

        if (existing.isPresent()) {
            int updated = capacityRepository.incrementOrderCount(existing.get().getId());
            return updated > 0;
        }

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        StoreCapacity capacity = new StoreCapacity();
        capacity.setStore(store);
        capacity.setDate(date);
        capacity.setHour(hour);
        capacity.setCurrentOrders(1);
        capacity.setMaxOrders(store.getCapacityOrdersPerHour());
        capacityRepository.save(capacity);
        return true;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "capacityCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanupOldCapacityData() {
        LocalDate cutoff = LocalDate.now().minusDays(7);
        int deleted = capacityRepository.deleteOlderThan(cutoff);
        log.info("Capacity cleanup: deleted {} records older than {}", deleted, cutoff);
    }
}
