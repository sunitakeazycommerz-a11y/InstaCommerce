package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.DeliveryStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.mapper.FulfillmentMapper;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.dto.response.TrackingResponse;
import com.instacommerce.fulfillment.dto.response.TrackingTimelineEntry;
import com.instacommerce.fulfillment.event.OrderStatusUpdateEvent;
import com.instacommerce.fulfillment.exception.DeliveryNotFoundException;
import com.instacommerce.fulfillment.exception.NoAvailableRiderException;
import com.instacommerce.fulfillment.exception.PickTaskNotFoundException;
import com.instacommerce.fulfillment.exception.RiderNotFoundException;
import com.instacommerce.fulfillment.repository.DeliveryRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.repository.RiderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {
    private static final Logger logger = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryRepository deliveryRepository;
    private final PickTaskRepository pickTaskRepository;
    private final RiderRepository riderRepository;
    private final RiderAssignmentService riderAssignmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxService outboxService;
    private final FulfillmentProperties fulfillmentProperties;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           PickTaskRepository pickTaskRepository,
                           RiderRepository riderRepository,
                           RiderAssignmentService riderAssignmentService,
                           ApplicationEventPublisher eventPublisher,
                           OutboxService outboxService,
                           FulfillmentProperties fulfillmentProperties) {
        this.deliveryRepository = deliveryRepository;
        this.pickTaskRepository = pickTaskRepository;
        this.riderRepository = riderRepository;
        this.riderAssignmentService = riderAssignmentService;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
        this.fulfillmentProperties = fulfillmentProperties;
    }

    @Deprecated(since = "wave-23", forRemoval = true)
    @Transactional
    public Optional<DeliveryResponse> assignRider(PickTask task) {
        Optional<Delivery> existing = deliveryRepository.findByOrderId(task.getOrderId());
        if (existing.isPresent() && existing.get().getStatus() != DeliveryStatus.FAILED) {
            return Optional.of(FulfillmentMapper.toDeliveryResponse(existing.get()));
        }
        Rider rider;
        try {
            rider = riderAssignmentService.assignRider(task.getStoreId());
        } catch (NoAvailableRiderException ex) {
            logger.warn("No available rider for store {}", task.getStoreId());
            return Optional.empty();
        }
        rider.setAvailable(false);
        riderRepository.save(rider);

        Delivery saved = assignDelivery(existing.orElse(null), task.getOrderId(), rider,
            fulfillmentProperties.getDelivery().getDefaultEtaMinutes(), task.getUserId());
        return Optional.of(FulfillmentMapper.toDeliveryResponse(saved));
    }

    @Transactional
    public DeliveryResponse assignRider(UUID orderId, UUID riderId, Integer etaMinutes) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId));
        if (!rider.isAvailable()) {
            throw new NoAvailableRiderException(task.getStoreId());
        }
        Delivery existing = deliveryRepository.findByOrderId(orderId).orElse(null);
        if (existing != null && existing.getStatus() == DeliveryStatus.DELIVERED) {
            return FulfillmentMapper.toDeliveryResponse(existing);
        }
        rider.setAvailable(false);
        riderRepository.save(rider);
        int eta = etaMinutes != null ? etaMinutes : fulfillmentProperties.getDelivery().getDefaultEtaMinutes();
        Delivery saved = assignDelivery(existing, orderId, rider, eta, task.getUserId());
        return FulfillmentMapper.toDeliveryResponse(saved);
    }

    @Transactional
    public DeliveryResponse markDelivered(UUID orderId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
            .orElseThrow(() -> new DeliveryNotFoundException(orderId));
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return FulfillmentMapper.toDeliveryResponse(delivery);
        }
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now());
        if (delivery.getRider() != null) {
            delivery.getRider().setAvailable(true);
            riderRepository.save(delivery.getRider());
        }
        Delivery saved = deliveryRepository.save(delivery);
        eventPublisher.publishEvent(new OrderStatusUpdateEvent(orderId, "DELIVERED", "delivered"));
        UUID userId = pickTaskRepository.findByOrderId(orderId)
            .map(PickTask::getUserId)
            .orElse(null);
        if (userId != null) {
            outboxService.publish("Fulfillment", orderId.toString(), "OrderDelivered",
                Map.of(
                    "orderId", orderId,
                    "userId", userId,
                    "deliveredAt", saved.getDeliveredAt()
                ));
        } else {
            logger.warn("Skipping OrderDelivered event for order {} — userId is null (possibly GDPR-erased)", orderId);
        }
        return FulfillmentMapper.toDeliveryResponse(saved);
    }

    @Transactional(readOnly = true)
    public TrackingResponse getTracking(UUID orderId, UUID requesterId, boolean isAdmin) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));
        if (!isAdmin && (requesterId == null || !requesterId.equals(task.getUserId()))) {
            throw new PickTaskNotFoundException(orderId);
        }
        Delivery delivery = deliveryRepository.findByOrderId(orderId).orElse(null);
        List<TrackingTimelineEntry> timeline = buildTimeline(task, delivery);
        return new TrackingResponse(
            orderId,
            resolveStatus(task, delivery),
            delivery != null && delivery.getRider() != null ? delivery.getRider().getName() : null,
            delivery != null ? delivery.getEstimatedMinutes() : null,
            delivery != null ? delivery.getDispatchedAt() : null,
            timeline
        );
    }

    private String resolveStatus(PickTask task, Delivery delivery) {
        if (delivery != null) {
            if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
                return "DELIVERED";
            }
            if (delivery.getStatus() == DeliveryStatus.ASSIGNED
                || delivery.getStatus() == DeliveryStatus.PICKED_UP
                || delivery.getStatus() == DeliveryStatus.IN_TRANSIT) {
                return "OUT_FOR_DELIVERY";
            }
        }
        if (task.getStatus() == PickTaskStatus.COMPLETED) {
            return "PACKED";
        }
        if (task.getStatus() == PickTaskStatus.IN_PROGRESS) {
            return "PACKING";
        }
        if (task.getStatus() == PickTaskStatus.CANCELLED) {
            return "CANCELLED";
        }
        return "PLACED";
    }

    private List<TrackingTimelineEntry> buildTimeline(PickTask task, Delivery delivery) {
        List<TrackingTimelineEntry> timeline = new ArrayList<>();
        if (task.getCreatedAt() != null) {
            timeline.add(new TrackingTimelineEntry("PLACED", task.getCreatedAt()));
        }
        if (task.getStartedAt() != null) {
            timeline.add(new TrackingTimelineEntry("PACKING", task.getStartedAt()));
        }
        if (task.getCompletedAt() != null) {
            timeline.add(new TrackingTimelineEntry("PACKED", task.getCompletedAt()));
        }
        if (delivery != null && delivery.getDispatchedAt() != null) {
            timeline.add(new TrackingTimelineEntry("OUT_FOR_DELIVERY", delivery.getDispatchedAt()));
        }
        if (delivery != null && delivery.getDeliveredAt() != null) {
            timeline.add(new TrackingTimelineEntry("DELIVERED", delivery.getDeliveredAt()));
        }
        timeline.sort(Comparator.comparing(TrackingTimelineEntry::at));
        return timeline;
    }

    private Delivery assignDelivery(Delivery existing, UUID orderId, Rider rider, int etaMinutes, UUID userId) {
        Delivery delivery = existing == null ? new Delivery() : existing;
        delivery.setOrderId(orderId);
        delivery.setRider(rider);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setEstimatedMinutes(etaMinutes);
        delivery.setDispatchedAt(Instant.now());
        Delivery saved = deliveryRepository.save(delivery);

        eventPublisher.publishEvent(new OrderStatusUpdateEvent(orderId, "OUT_FOR_DELIVERY", "rider-assigned"));
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("orderId", orderId);
        eventPayload.put("userId", userId);
        eventPayload.put("riderId", rider.getId());
        eventPayload.put("riderName", rider.getName());
        eventPayload.put("estimatedMinutes", saved.getEstimatedMinutes());
        eventPayload.put("dispatchedAt", saved.getDispatchedAt());
        outboxService.publish("Fulfillment", orderId.toString(), "OrderDispatched", eventPayload);
        return saved;
    }
}
