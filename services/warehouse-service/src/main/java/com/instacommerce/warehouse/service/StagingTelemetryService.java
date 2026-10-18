package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.StagingTemperatureTelemetry;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.dto.request.DispatchPackagingRequest;
import com.instacommerce.warehouse.dto.request.StagingTelemetryRequest;
import com.instacommerce.warehouse.dto.response.DispatchPackagingResponse;
import com.instacommerce.warehouse.dto.response.StagingTelemetryResponse;
import com.instacommerce.warehouse.exception.ColdChainPackagingViolationException;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StagingTelemetryRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StagingTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(StagingTelemetryService.class);

    public static final BigDecimal CHILLED_MAX_TEMP_CELSIUS = new BigDecimal("4.00");
    public static final BigDecimal FROZEN_MAX_TEMP_CELSIUS = new BigDecimal("-18.00");

    private final StagingTelemetryRepository telemetryRepository;
    private final StoreRepository storeRepository;
    private final OutboxService outboxService;

    public StagingTelemetryService(StagingTelemetryRepository telemetryRepository,
                                   StoreRepository storeRepository,
                                   OutboxService outboxService) {
        this.telemetryRepository = telemetryRepository;
        this.storeRepository = storeRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public StagingTelemetryResponse recordTelemetry(UUID storeId, StagingTelemetryRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));

        boolean breached = isBreached(request.storageType(), request.temperatureCelsius());
        boolean alertTriggered = false;

        if (breached) {
            Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
            long priorBreaches = telemetryRepository
                    .countByStoreIdAndBayIdAndBreachedTrueAndRecordedAtAfter(storeId, request.bayId(), fiveMinutesAgo);

            // Trigger alert if breached repeatedly or persisted across 5-minute sampling
            if (priorBreaches >= 1) {
                alertTriggered = true;
                log.warn("COLD CHAIN BREACH ALERT: Staging bay {} in store {} at {}C exceeds threshold for {}",
                        request.bayId(), storeId, request.temperatureCelsius(), request.storageType());

                outboxService.publish(
                        "StagingBay",
                        request.bayId(),
                        "ColdChainBreachAlert",
                        Map.of(
                                "storeId", storeId.toString(),
                                "bayId", request.bayId(),
                                "sensorId", request.sensorId(),
                                "storageType", request.storageType(),
                                "temperatureCelsius", request.temperatureCelsius(),
                                "timestamp", Instant.now().toString()
                        )
                );
            }
        }

        StagingTemperatureTelemetry telemetry = new StagingTemperatureTelemetry();
        telemetry.setStore(store);
        telemetry.setSensorId(request.sensorId());
        telemetry.setBayId(request.bayId());
        telemetry.setStorageType(request.storageType());
        telemetry.setTemperatureCelsius(request.temperatureCelsius());
        telemetry.setBatteryPct(request.batteryPct());
        telemetry.setRecordedAt(Instant.now());
        telemetry.setBreached(breached);
        telemetry.setAlertTriggered(alertTriggered);

        StagingTemperatureTelemetry saved = telemetryRepository.save(telemetry);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<StagingTelemetryResponse> getRecentTelemetry(UUID storeId, String bayId) {
        return telemetryRepository.findTop10ByStoreIdAndBayIdOrderByRecordedAtDesc(storeId, bayId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StagingTelemetryResponse> getAllTelemetryForStore(UUID storeId) {
        return telemetryRepository.findByStoreIdOrderByRecordedAtDesc(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    public DispatchPackagingResponse validateDispatchPackaging(UUID storeId, DispatchPackagingRequest request) {
        String type = request.storageType().toUpperCase();
        if ("CHILLED".equals(type) || "FROZEN".equals(type)) {
            if (!Boolean.TRUE.equals(request.hasInsulatedThermalBag()) || !Boolean.TRUE.equals(request.hasColdPacks())) {
                log.warn("Cold chain packaging check failed for order {} in store {}: thermalBag={}, coldPacks={}",
                        request.orderId(), storeId, request.hasInsulatedThermalBag(), request.hasColdPacks());
                throw new ColdChainPackagingViolationException(
                        "Insulated thermal bag and cold packs are mandatory for " + type + " cold-chain dispatches");
            }
        }

        return new DispatchPackagingResponse(
                request.orderId(),
                request.storageType(),
                true,
                "Cold chain packaging verified successfully",
                Instant.now()
        );
    }

    private boolean isBreached(String storageType, BigDecimal temp) {
        if (temp == null || storageType == null) {
            return false;
        }
        String normalized = storageType.trim().toUpperCase();
        if ("CHILLED".equals(normalized)) {
            return temp.compareTo(CHILLED_MAX_TEMP_CELSIUS) > 0;
        } else if ("FROZEN".equals(normalized)) {
            return temp.compareTo(FROZEN_MAX_TEMP_CELSIUS) > 0;
        }
        return false;
    }

    private StagingTelemetryResponse toResponse(StagingTemperatureTelemetry t) {
        return new StagingTelemetryResponse(
                t.getId(),
                t.getStore().getId(),
                t.getSensorId(),
                t.getBayId(),
                t.getStorageType(),
                t.getTemperatureCelsius(),
                t.getBatteryPct(),
                t.getRecordedAt(),
                t.isBreached(),
                t.isAlertTriggered()
        );
    }
}
