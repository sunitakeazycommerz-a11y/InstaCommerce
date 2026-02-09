package com.instacommerce.routing.service;

import com.instacommerce.routing.domain.model.DeliveryTracking;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.dto.response.TrackingResponse;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.repository.DeliveryTrackingRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    private final DeliveryTrackingRepository trackingRepository;
    private final DeliveryRepository deliveryRepository;

    public TrackingService(DeliveryTrackingRepository trackingRepository,
                           DeliveryRepository deliveryRepository) {
        this.trackingRepository = trackingRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional
    public TrackingResponse recordLocation(LocationUpdateRequest request) {
        if (!deliveryRepository.existsById(request.deliveryId())) {
            throw new DeliveryNotFoundException(request.deliveryId());
        }

        DeliveryTracking tracking = new DeliveryTracking();
        tracking.setDeliveryId(request.deliveryId());
        tracking.setLatitude(request.latitude());
        tracking.setLongitude(request.longitude());
        tracking.setSpeedKmh(request.speedKmh());
        tracking.setHeading(request.heading());

        tracking = trackingRepository.save(tracking);
        log.debug("Recorded location for delivery {}: ({}, {})",
            request.deliveryId(), request.latitude(), request.longitude());

        return toResponse(tracking);
    }

    @Transactional(readOnly = true)
    public TrackingResponse getLatestLocation(UUID deliveryId) {
        return trackingRepository.findLatestByDeliveryId(deliveryId)
            .map(this::toResponse)
            .orElseThrow(() -> new DeliveryNotFoundException(
                "No tracking data found for delivery " + deliveryId));
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
}
