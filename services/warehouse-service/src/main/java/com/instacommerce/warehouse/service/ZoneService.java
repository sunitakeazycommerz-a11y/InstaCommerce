package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.request.CreateZoneRequest;
import com.instacommerce.warehouse.dto.request.UpdateZoneRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.exception.ZoneAlreadyExistsException;
import com.instacommerce.warehouse.exception.ZoneNotFoundException;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {

    private static final BigDecimal DEFAULT_RADIUS_KM = new BigDecimal("5.00");

    private final StoreZoneRepository storeZoneRepository;
    private final StoreRepository storeRepository;

    public ZoneService(StoreZoneRepository storeZoneRepository, StoreRepository storeRepository) {
        this.storeZoneRepository = storeZoneRepository;
        this.storeRepository = storeRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "store-zones", key = "#pincode")
    public List<UUID> mapPincodeToStoreIds(String pincode) {
        return storeZoneRepository.findActiveZonesByPincode(pincode).stream()
                .map(zone -> zone.getStore().getId())
                .toList();
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
