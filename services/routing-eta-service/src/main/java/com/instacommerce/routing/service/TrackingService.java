package com.instacommerce.routing.service;

import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.domain.model.DeliveryTracking;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.dto.response.TrackingResponse;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.repository.DeliveryTrackingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double GEOFENCE_RADIUS_KM = 0.2;

    private final DeliveryTrackingRepository trackingRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;
    private final SimpMessagingTemplate messagingTemplate;

    public TrackingService(DeliveryTrackingRepository trackingRepository,
                           DeliveryRepository deliveryRepository,
                           DeliveryService deliveryService,
                           SimpMessagingTemplate messagingTemplate) {
        this.trackingRepository = trackingRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public TrackingResponse recordLocation(LocationUpdateRequest request) {
        Delivery delivery = deliveryRepository.findById(request.deliveryId())
            .orElseThrow(() -> new DeliveryNotFoundException(request.deliveryId()));

        DeliveryTracking tracking = new DeliveryTracking();
        tracking.setDeliveryId(request.deliveryId());
        tracking.setLatitude(request.latitude());
        tracking.setLongitude(request.longitude());
        tracking.setSpeedKmh(request.speedKmh());
        tracking.setHeading(request.heading());

        tracking = trackingRepository.save(tracking);
        log.debug("Recorded location for delivery {}: ({}, {})",
            request.deliveryId(), request.latitude(), request.longitude());

        TrackingResponse response = toResponse(tracking);
        messagingTemplate.convertAndSend("/topic/tracking/" + request.deliveryId(), response);
        maybeTriggerNearDestination(delivery, request.latitude(), request.longitude());
        return response;
    }

    @Transactional(readOnly = true)
    public TrackingResponse getLatestLocation(UUID deliveryId) {
        return trackingRepository.findLatestByDeliveryId(deliveryId)
            .map(this::toResponse)
            .orElseThrow(() -> new DeliveryNotFoundException(
                "No tracking data found for delivery " + deliveryId));
    }

    @Transactional(readOnly = true)
    public Optional<TrackingResponse> findLatestLocation(UUID deliveryId) {
        return trackingRepository.findLatestByDeliveryId(deliveryId)
            .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<TrackingResponse> getTrackingHistory(UUID deliveryId) {
        return trackingRepository.findByDeliveryIdOrderByRecordedAtDesc(deliveryId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private TrackingResponse toResponse(DeliveryTracking t) {
        return new TrackingResponse(
            t.getLatitude(), t.getLongitude(),
            t.getSpeedKmh(), t.getHeading(), t.getRecordedAt());
    }

    private void maybeTriggerNearDestination(Delivery delivery, BigDecimal latitude, BigDecimal longitude) {
        if (delivery.getStatus() != DeliveryStatus.EN_ROUTE) {
            return;
        }
        double distanceKm = haversineDistance(
            latitude.doubleValue(), longitude.doubleValue(),
            delivery.getDropoffLat().doubleValue(), delivery.getDropoffLng().doubleValue());
        if (distanceKm <= GEOFENCE_RADIUS_KM) {
            deliveryService.updateStatus(delivery.getId(), DeliveryStatus.NEAR_DESTINATION);
        }
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
