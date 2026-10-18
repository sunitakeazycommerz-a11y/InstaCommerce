package com.instacommerce.riderfleet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.exception.InvalidRiderStateException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAvailabilityService {
    private static final Logger logger = LoggerFactory.getLogger(RiderAvailabilityService.class);

    private final RiderAvailabilityRepository availabilityRepository;
    private final RiderRepository riderRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RiderAvailabilityService(RiderAvailabilityRepository availabilityRepository,
                                    RiderRepository riderRepository,
                                    ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                    ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
                                    ObjectMapper objectMapper) {
        this.availabilityRepository = availabilityRepository;
        this.riderRepository = riderRepository;
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        this.kafkaTemplate = kafkaTemplateProvider != null ? kafkaTemplateProvider.getIfAvailable() : null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RiderAvailabilityService(RiderAvailabilityRepository availabilityRepository,
                                    RiderRepository riderRepository,
                                    StringRedisTemplate redisTemplate,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper) {
        this.availabilityRepository = availabilityRepository;
        this.riderRepository = riderRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Ingests high-frequency GPS location pings from riders.
     * To eliminate PostgreSQL lock contention and connection pool exhaustion under high concurrency (Issue #584),
     * location pings are strictly written to Redis geospatial index (GEOADD riders:active) and buffered
     * in Kafka (rider.location.updates) without touching synchronous database locks.
     */
    public void updateLocation(UUID riderId, BigDecimal lat, BigDecimal lng) {
        if (riderId == null || lat == null || lng == null) {
            return;
        }

        // 1. Ingest high-frequency rider pings strictly into Redis geospatial index (GEOADD riders:active)
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForGeo().add("riders:active", new Point(lng.doubleValue(), lat.doubleValue()), riderId.toString());
                Map<String, String> locationMap = Map.of(
                    "lat", lat.toPlainString(),
                    "lng", lng.toPlainString(),
                    "updated_at", Instant.now().toString()
                );
                redisTemplate.opsForHash().putAll("rider:location:" + riderId, locationMap);
            } catch (Exception e) {
                logger.warn("Failed to update rider location in Redis for riderId={}: {}", riderId, e.getMessage());
            }
        }

        // 2. Buffer location history in Kafka (rider.location.updates) for async batch storage in BigQuery
        if (kafkaTemplate != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("rider_id", riderId.toString());
                payload.put("latitude", lat.doubleValue());
                payload.put("longitude", lng.doubleValue());
                payload.put("timestamp", Instant.now().toString());

                String json = objectMapper.writeValueAsString(payload);
                kafkaTemplate.send(TopicNames.RIDER_LOCATION_UPDATES, riderId.toString(), json);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize rider location update event for riderId={}: {}", riderId, e.getMessage());
            } catch (Exception e) {
                logger.warn("Failed to publish rider location update to Kafka for riderId={}: {}", riderId, e.getMessage());
            }
        }

        logger.debug("Ingested high-frequency location ping for rider id={} lat={} lng={}", riderId, lat, lng);
    }

    @Transactional
    public void toggleAvailability(UUID riderId, boolean available) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        if (rider.getStatus() != RiderStatus.ACTIVE) {
            throw new InvalidRiderStateException(
                "Rider must be ACTIVE to toggle availability, current: " + rider.getStatus());
        }

        RiderAvailability availability = availabilityRepository.findByRiderId(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        availability.setAvailable(available);
        availabilityRepository.save(availability);
        logger.info("Toggled availability for rider id={} available={}", riderId, available);
    }
}
