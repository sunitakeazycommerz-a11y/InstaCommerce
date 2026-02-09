package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.config.WarehouseProperties;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.dto.mapper.StoreMapper;
import com.instacommerce.warehouse.dto.request.CreateStoreRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreHoursRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);
    private final StoreRepository storeRepository;
    private final StoreHoursRepository storeHoursRepository;
    private final OutboxService outboxService;
    private final WarehouseProperties warehouseProperties;

    public StoreService(StoreRepository storeRepository,
                        StoreHoursRepository storeHoursRepository,
                        OutboxService outboxService,
                        WarehouseProperties warehouseProperties) {
        this.storeRepository = storeRepository;
        this.storeHoursRepository = storeHoursRepository;
        this.outboxService = outboxService;
        this.warehouseProperties = warehouseProperties;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "stores", key = "#id")
    public StoreResponse getStore(UUID id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new StoreNotFoundException(id));
        return StoreMapper.toResponse(store);
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findNearestStores(double lat, double lng, Double radiusKm) {
        double radius = radiusKm != null ? radiusKm : warehouseProperties.getNearestStore().getDefaultRadiusKm();
        int maxResults = warehouseProperties.getNearestStore().getMaxResults();
        return storeRepository.findNearestStores(lat, lng, radius, maxResults).stream()
                .map(StoreMapper::toResponseWithoutRelations)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findByCity(String city) {
        return storeRepository.findByCity(city).stream()
                .map(StoreMapper::toResponseWithoutRelations)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findByStatus(StoreStatus status) {
        return storeRepository.findByStatus(status).stream()
                .map(StoreMapper::toResponseWithoutRelations)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isStoreOpen(UUID storeId, LocalDateTime now) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        if (store.getStatus() != StoreStatus.ACTIVE) {
            return false;
        }
        ZonedDateTime storeNow = toStoreTime(store, now);
        int dayOfWeek = storeNow.getDayOfWeek().getValue() % 7;
        LocalTime currentTime = storeNow.toLocalTime();

        Optional<StoreHours> hours = storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, dayOfWeek);
        if (hours.isPresent() && isWithinOperatingHours(hours.get(), currentTime)) {
            return true;
        }

        int previousDay = (dayOfWeek + 6) % 7;
        Optional<StoreHours> previousHours = storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, previousDay);
        return previousHours.isPresent()
                && !previousHours.get().isHoliday()
                && previousHours.get().getClosesAt().isBefore(previousHours.get().getOpensAt())
                && !currentTime.isAfter(previousHours.get().getClosesAt());
    }

    @Transactional
    @CacheEvict(value = "stores", key = "#result.id()")
    public StoreResponse createStore(CreateStoreRequest request) {
        Store store = new Store();
        store.setName(request.name());
        store.setAddress(request.address());
        store.setCity(request.city());
        store.setState(request.state());
        store.setPincode(request.pincode());
        store.setLatitude(request.latitude());
        store.setLongitude(request.longitude());
        store.setTimezone(resolveZoneId(request.timezone()).getId());
        store.setCapacityOrdersPerHour(
                request.capacityOrdersPerHour() != null ? request.capacityOrdersPerHour() : 100);
        store.setStatus(StoreStatus.ACTIVE);

        Store saved = storeRepository.save(store);
        outboxService.publish("Store", saved.getId().toString(), "StoreCreated",
                Map.of("storeId", saved.getId(), "name", saved.getName(),
                        "city", saved.getCity(), "status", saved.getStatus().name()));
        log.info("Created store {} in {}", saved.getId(), saved.getCity());
        return StoreMapper.toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "stores", key = "#id")
    public StoreResponse updateStoreStatus(UUID id, StoreStatus status) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new StoreNotFoundException(id));
        StoreStatus previous = store.getStatus();
        store.setStatus(status);
        Store saved = storeRepository.save(store);
        outboxService.publish("Store", saved.getId().toString(), "StoreStatusChanged",
                Map.of("storeId", saved.getId(), "previousStatus", previous.name(),
                        "newStatus", status.name()));
        log.info("Store {} status changed from {} to {}", id, previous, status);
        return StoreMapper.toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "stores", key = "#id")
    public void deleteStore(UUID id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new StoreNotFoundException(id));
        storeRepository.delete(store);
        outboxService.publish("Store", id.toString(), "StoreDeleted",
                Map.of("storeId", id, "name", store.getName()));
        log.info("Deleted store {}", id);
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

    private boolean isWithinOperatingHours(StoreHours hours, LocalTime currentTime) {
        if (hours.isHoliday()) {
            return false;
        }
        LocalTime opensAt = hours.getOpensAt();
        LocalTime closesAt = hours.getClosesAt();
        if (closesAt.equals(opensAt)) {
            return true;
        }
        if (closesAt.isBefore(opensAt)) {
            return !currentTime.isBefore(opensAt);
        }
        return !currentTime.isBefore(opensAt) && !currentTime.isAfter(closesAt);
    }
}
