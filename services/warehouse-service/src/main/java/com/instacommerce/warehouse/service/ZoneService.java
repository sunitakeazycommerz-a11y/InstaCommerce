package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.request.CreateZoneRequest;
import com.instacommerce.warehouse.dto.request.UpdateZoneRequest;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.dto.response.ZoneResolutionResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.exception.ZoneAlreadyExistsException;
import com.instacommerce.warehouse.exception.ZoneNotFoundException;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {

    private static final BigDecimal DEFAULT_RADIUS_KM = new BigDecimal("5.00");
    private static final double DEFAULT_FALLBACK_RADIUS_KM = 15.0;
    private static final int DEFAULT_FALLBACK_MAX_RESULTS = 10;

    private final StoreZoneRepository storeZoneRepository;
    private final StoreRepository storeRepository;
    private final StoreService storeService;
    private final CapacityService capacityService;

    public ZoneService(StoreZoneRepository storeZoneRepository,
                       StoreRepository storeRepository,
                       StoreService storeService,
                       CapacityService capacityService) {
        this.storeZoneRepository = Objects.requireNonNull(storeZoneRepository, "storeZoneRepository must not be null");
        this.storeRepository = Objects.requireNonNull(storeRepository, "storeRepository must not be null");
        this.storeService = Objects.requireNonNull(storeService, "storeService must not be null");
        this.capacityService = Objects.requireNonNull(capacityService, "capacityService must not be null");
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "store-zones", key = "#pincode")
    public List<UUID> mapPincodeToStoreIds(String pincode) {
        return storeZoneRepository.findActiveZonesByPincode(pincode).stream()
                .map(zone -> zone.getStore().getId())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ZoneResolutionResponse> resolveStoresForOrder(String pincode, Double lat, Double lng) {
        String normalizedPincode = normalizePincode(pincode);
        List<StoreZone> primaryZones = storeZoneRepository.findActiveZonesByPincode(normalizedPincode);
        Set<UUID> checkedStoreIds = new LinkedHashSet<>();
        List<ZoneResolutionResponse> eligiblePrimary = new ArrayList<>();

        for (StoreZone zone : primaryZones) {
            Store store = zone.getStore();
            if (store == null || !checkedStoreIds.add(store.getId())) {
                continue;
            }
            ZoneResolutionResponse candidate = evaluateStoreCandidate(store, normalizedPincode, "PRIMARY_PINCODE");
            if (candidate.open() && candidate.canAcceptOrders()) {
                eligiblePrimary.add(candidate);
            }
        }
        if (!eligiblePrimary.isEmpty()) {
            eligiblePrimary.sort(Comparator.comparingInt(this::throttlePriority)
                    .thenComparingInt(ZoneResolutionResponse::etaExtensionMinutes));
            return eligiblePrimary;
        }

        List<Store> fallbackCandidates = (lat != null && lng != null)
                ? storeRepository.findNearestStores(lat, lng, DEFAULT_FALLBACK_RADIUS_KM, DEFAULT_FALLBACK_MAX_RESULTS)
                : storeRepository.findByStatus(StoreStatus.ACTIVE);
        List<ZoneResolutionResponse> eligibleFallback = new ArrayList<>();
        for (Store store : fallbackCandidates) {
            if (store == null || checkedStoreIds.contains(store.getId())) {
                continue;
            }
            checkedStoreIds.add(store.getId());
            ZoneResolutionResponse candidate = evaluateStoreCandidate(store, normalizedPincode, "FALLBACK_NEAREST");
            if (candidate.open() && candidate.canAcceptOrders()) {
                eligibleFallback.add(candidate);
            }
        }
        eligibleFallback.sort(Comparator.comparingInt(this::throttlePriority)
                .thenComparingInt(ZoneResolutionResponse::etaExtensionMinutes));
        return eligibleFallback;
    }

    private ZoneResolutionResponse evaluateStoreCandidate(Store store, String pincode, String source) {
        if (store.getStatus() != StoreStatus.ACTIVE) {
            return new ZoneResolutionResponse(store.getId(), store.getName(), pincode, source,
                    false, false, "PAUSED", 0);
        }
        boolean open = storeService.isStoreOpen(store.getId(), (Instant) null);
        CapacityResponse capacity = capacityService.getStoreCapacity(store.getId(), null);
        boolean canAccept = open && capacity.canAcceptOrders();
        return new ZoneResolutionResponse(
                store.getId(),
                store.getName(),
                pincode,
                source,
                open,
                canAccept,
                capacity.throttleStatus(),
                capacity.etaExtensionMinutes());
    }

    private int throttlePriority(ZoneResolutionResponse r) {
        return switch (r.throttleStatus()) {
            case "NORMAL" -> 0;
            case "SURGE" -> 1;
            case "THROTTLED" -> 2;
            default -> 3;
        };
    }

    @Transactional(readOnly = true)
    public List<StoreResponse.ZoneResponse> getStoreZones(UUID storeId) {
        return storeZoneRepository.findByStoreId(storeId).stream()
                .map(this::toZoneResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse.ZoneResponse getZone(UUID storeId, UUID zoneId) {
        return toZoneResponse(findZoneForStore(storeId, zoneId));
    }

    @Transactional(readOnly = true)
    public BigDecimal getDeliveryRadius(UUID storeId) {
        return storeZoneRepository.findByStoreId(storeId).stream()
                .map(StoreZone::getDeliveryRadiusKm)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "store-zones", allEntries = true),
        @CacheEvict(value = "stores", key = "#storeId")
    })
    public StoreResponse.ZoneResponse createZone(UUID storeId, CreateZoneRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        String zoneName = normalizeRequired(request.zoneName(), "zoneName");
        String pincode = normalizePincode(request.pincode());
        if (storeZoneRepository.findByStoreIdAndPincode(storeId, pincode).isPresent()) {
            throw new ZoneAlreadyExistsException(storeId, pincode);
        }

        StoreZone zone = new StoreZone();
        zone.setStore(store);
        zone.setZoneName(zoneName);
        zone.setPincode(pincode);
        zone.setDeliveryRadiusKm(request.deliveryRadiusKm() != null ? request.deliveryRadiusKm() : DEFAULT_RADIUS_KM);
        return toZoneResponse(storeZoneRepository.save(zone));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "store-zones", allEntries = true),
        @CacheEvict(value = "stores", key = "#storeId")
    })
    public StoreResponse.ZoneResponse updateZone(UUID storeId, UUID zoneId, UpdateZoneRequest request) {
        StoreZone zone = findZoneForStore(storeId, zoneId);
        if (request.zoneName() != null) {
            zone.setZoneName(normalizeRequired(request.zoneName(), "zoneName"));
        }
        if (request.pincode() != null) {
            String pincode = normalizePincode(request.pincode());
            if (!pincode.equals(zone.getPincode())) {
                storeZoneRepository.findByStoreIdAndPincode(storeId, pincode)
                        .filter(existing -> !existing.getId().equals(zoneId))
                        .ifPresent(existing -> {
                            throw new ZoneAlreadyExistsException(storeId, pincode);
                        });
                zone.setPincode(pincode);
            }
        }
        if (request.deliveryRadiusKm() != null) {
            zone.setDeliveryRadiusKm(request.deliveryRadiusKm());
        }
        return toZoneResponse(storeZoneRepository.save(zone));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "store-zones", allEntries = true),
        @CacheEvict(value = "stores", key = "#storeId")
    })
    public void deleteZone(UUID storeId, UUID zoneId) {
        StoreZone zone = findZoneForStore(storeId, zoneId);
        storeZoneRepository.delete(zone);
    }

    private StoreZone findZoneForStore(UUID storeId, UUID zoneId) {
        return storeZoneRepository.findByIdAndStoreId(zoneId, storeId)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String normalizePincode(String pincode) {
        String normalized = normalizeRequired(pincode, "pincode");
        if (normalized.length() < 5 || normalized.length() > 10) {
            throw new IllegalArgumentException("pincode length must be between 5 and 10");
        }
        return normalized;
    }

    private StoreResponse.ZoneResponse toZoneResponse(StoreZone zone) {
        return new StoreResponse.ZoneResponse(
                zone.getId(),
                zone.getZoneName(),
                zone.getPincode(),
                zone.getDeliveryRadiusKm());
    }
}
