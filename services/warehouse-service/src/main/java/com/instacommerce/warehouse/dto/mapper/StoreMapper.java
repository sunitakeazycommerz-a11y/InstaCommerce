package com.instacommerce.warehouse.dto.mapper;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import java.util.List;

public final class StoreMapper {

    private StoreMapper() {
    }

    public static StoreResponse toResponse(Store store) {
        List<StoreResponse.ZoneResponse> zones = store.getZones().stream()
                .map(StoreMapper::toZoneResponse)
                .toList();
        List<StoreResponse.HoursResponse> hours = store.getHours().stream()
                .map(StoreMapper::toHoursResponse)
                .toList();
        return new StoreResponse(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getCity(),
                store.getState(),
                store.getPincode(),
                store.getLatitude(),
                store.getLongitude(),
                store.getStatus().name(),
                store.getCapacityOrdersPerHour(),
                zones,
                hours,
                store.getCreatedAt(),
                store.getUpdatedAt());
    }

    public static StoreResponse toResponseWithoutRelations(Store store) {
        return new StoreResponse(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getCity(),
                store.getState(),
                store.getPincode(),
                store.getLatitude(),
                store.getLongitude(),
                store.getStatus().name(),
                store.getCapacityOrdersPerHour(),
                List.of(),
                List.of(),
                store.getCreatedAt(),
                store.getUpdatedAt());
    }

    public static CapacityResponse toCapacityResponse(StoreCapacity capacity) {
        return new CapacityResponse(
                capacity.getStore().getId(),
                capacity.getDate(),
                capacity.getHour(),
                capacity.getCurrentOrders(),
                capacity.getMaxOrders(),
                capacity.getCurrentOrders() < capacity.getMaxOrders());
    }

    private static StoreResponse.ZoneResponse toZoneResponse(StoreZone zone) {
        return new StoreResponse.ZoneResponse(
                zone.getId(),
                zone.getZoneName(),
                zone.getPincode(),
                zone.getDeliveryRadiusKm());
    }

    private static StoreResponse.HoursResponse toHoursResponse(StoreHours hours) {
        return new StoreResponse.HoursResponse(
                hours.getId(),
                hours.getDayOfWeek(),
                hours.getOpensAt().toString(),
                hours.getClosesAt().toString(),
                hours.isHoliday());
    }
}
