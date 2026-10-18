package com.instacommerce.riderfleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderAvailabilityServiceTest {

    @Mock
    private RiderAvailabilityRepository availabilityRepository;

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private RiderAvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        availabilityService = new RiderAvailabilityService(
            availabilityRepository,
            riderRepository,
            redisTemplate,
            kafkaTemplate,
            objectMapper
        );
    }

    @Test
    @DisplayName("updateLocation ingests ping strictly into Redis GEOADD and Kafka without PostgreSQL lock contention")
    void shouldUpdateLocationViaRedisAndKafkaWithoutDatabaseLocks() {
        UUID riderId = UUID.randomUUID();
        BigDecimal lat = new BigDecimal("12.9715987");
        BigDecimal lng = new BigDecimal("77.5945627");

        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        availabilityService.updateLocation(riderId, lat, lng);

        // Verify Redis GEOADD riders:active
        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).add(eq("riders:active"), pointCaptor.capture(), eq(riderId.toString()));
        assertEquals(lng.doubleValue(), pointCaptor.getValue().getX(), 0.0001);
        assertEquals(lat.doubleValue(), pointCaptor.getValue().getY(), 0.0001);

        // Verify Redis Hash
        verify(hashOperations).putAll(eq("rider:location:" + riderId), any());

        // Verify Kafka topic publish
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TopicNames.RIDER_LOCATION_UPDATES), eq(riderId.toString()), payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains(riderId.toString()));
        assertTrue(payloadCaptor.getValue().contains("12.9715987"));

        // Verify NO PostgreSQL locks or repository interactions occurred on hot ping path
        verify(riderRepository, never()).findById(any());
        verify(availabilityRepository, never()).findByRiderId(any());
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateLocation handles null Redis and Kafka templates gracefully")
    void shouldHandleNullTemplatesGracefully() {
        RiderAvailabilityService fallbackService = new RiderAvailabilityService(
            availabilityRepository,
            riderRepository,
            (StringRedisTemplate) null,
            (KafkaTemplate<String, String>) null,
            objectMapper
        );

        UUID riderId = UUID.randomUUID();
        fallbackService.updateLocation(riderId, new BigDecimal("12.97"), new BigDecimal("77.59"));

        verify(riderRepository, never()).findById(any());
        verify(availabilityRepository, never()).save(any());
    }
}
