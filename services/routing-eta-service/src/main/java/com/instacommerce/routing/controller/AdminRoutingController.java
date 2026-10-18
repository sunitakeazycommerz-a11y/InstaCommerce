package com.instacommerce.routing.controller;

import com.instacommerce.routing.domain.model.RoutingBarrier;
import com.instacommerce.routing.domain.model.TrafficCongestionFeed;
import com.instacommerce.routing.domain.model.WeatherCondition;
import com.instacommerce.routing.repository.RoutingBarrierRepository;
import com.instacommerce.routing.repository.TrafficCongestionFeedRepository;
import com.instacommerce.routing.repository.WeatherConditionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/routing")
@Validated
public class AdminRoutingController {

    private final RoutingBarrierRepository barrierRepository;
    private final WeatherConditionRepository weatherRepository;
    private final TrafficCongestionFeedRepository trafficFeedRepository;

    public AdminRoutingController(RoutingBarrierRepository barrierRepository,
                                  WeatherConditionRepository weatherRepository,
                                  TrafficCongestionFeedRepository trafficFeedRepository) {
        this.barrierRepository = barrierRepository;
        this.weatherRepository = weatherRepository;
        this.trafficFeedRepository = trafficFeedRepository;
    }

    public record CreateBarrierRequest(
        @NotBlank String name,
        String reason,
        @NotNull BigDecimal minLat,
        @NotNull BigDecimal minLng,
        @NotNull BigDecimal maxLat,
        @NotNull BigDecimal maxLng,
        BigDecimal detourPenaltyKm,
        Integer detourPenaltyMinutes,
        Instant expiresAt
    ) {}

    public record SetWeatherRequest(
        @NotNull UUID storeId,
        @NotBlank String conditionType,
        @NotNull @DecimalMin("0.5") BigDecimal speedPenaltyMultiplier,
        boolean monsoonDelayBanner,
        String notes
    ) {}

    public record IngestTrafficRequest(
        @NotBlank String zoneId,
        @NotNull @DecimalMin("0.1") BigDecimal congestionFactor,
        String source
    ) {}

    // ── 1. Dynamic road barriers / bridge closures (#616) ─────────────────────

    @PostMapping("/barriers")
    public ResponseEntity<RoutingBarrier> createBarrier(@Valid @RequestBody CreateBarrierRequest request) {
        RoutingBarrier barrier = new RoutingBarrier();
        barrier.setName(request.name());
        barrier.setReason(request.reason());
        barrier.setMinLat(request.minLat());
        barrier.setMinLng(request.minLng());
        barrier.setMaxLat(request.maxLat());
        barrier.setMaxLng(request.maxLng());
        if (request.detourPenaltyKm() != null) {
            barrier.setDetourPenaltyKm(request.detourPenaltyKm());
        }
        if (request.detourPenaltyMinutes() != null) {
            barrier.setDetourPenaltyMinutes(request.detourPenaltyMinutes());
        }
        barrier.setExpiresAt(request.expiresAt());
        barrier.setActive(true);

        return ResponseEntity.ok(barrierRepository.save(barrier));
    }

    @GetMapping("/barriers")
    public ResponseEntity<List<RoutingBarrier>> getActiveBarriers() {
        return ResponseEntity.ok(barrierRepository.findByIsActiveTrue());
    }

    @DeleteMapping("/barriers/{id}")
    public ResponseEntity<Void> deactivateBarrier(@PathVariable UUID id) {
        barrierRepository.findById(id).ifPresent(barrier -> {
            barrier.setActive(false);
            barrierRepository.save(barrier);
        });
        return ResponseEntity.ok().build();
    }

    // ── 2. Monsoon & rainy weather multipliers (#698) ──────────────────────────

    @PostMapping("/weather")
    public ResponseEntity<WeatherCondition> updateWeather(@Valid @RequestBody SetWeatherRequest request) {
        WeatherCondition condition = weatherRepository.findByStoreId(request.storeId())
            .orElseGet(() -> {
                WeatherCondition wc = new WeatherCondition();
                wc.setStoreId(request.storeId());
                return wc;
            });

        condition.setConditionType(request.conditionType());
        condition.setSpeedPenaltyMultiplier(request.speedPenaltyMultiplier());
        condition.setMonsoonDelayBanner(request.monsoonDelayBanner());
        condition.setNotes(request.notes());

        return ResponseEntity.ok(weatherRepository.save(condition));
    }

    @GetMapping("/weather/{storeId}")
    public ResponseEntity<WeatherCondition> getWeather(@PathVariable UUID storeId) {
        return weatherRepository.findByStoreId(storeId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── 3. Live municipal traffic congestion feeds (#602) ─────────────────────

    @PostMapping("/traffic")
    public ResponseEntity<TrafficCongestionFeed> ingestTraffic(@Valid @RequestBody IngestTrafficRequest request) {
        TrafficCongestionFeed feed = trafficFeedRepository.findByZoneId(request.zoneId())
            .orElseGet(() -> {
                TrafficCongestionFeed f = new TrafficCongestionFeed();
                f.setZoneId(request.zoneId());
                return f;
            });

        feed.setCongestionFactor(request.congestionFactor());
        if (request.source() != null && !request.source().isBlank()) {
            feed.setSource(request.source());
        }

        return ResponseEntity.ok(trafficFeedRepository.save(feed));
    }

    @GetMapping("/traffic/{zoneId}")
    public ResponseEntity<TrafficCongestionFeed> getTraffic(@PathVariable String zoneId) {
        return trafficFeedRepository.findByZoneId(zoneId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
