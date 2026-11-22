package com.instacommerce.order.service;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.model.OrderStatusHistory;
import com.instacommerce.order.domain.statemachine.OrderStateMachine;
import com.instacommerce.order.dto.mapper.OrderMapper;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.exception.OrderNotFoundException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.platform.web.PageableUtils;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OrderOutboxPublisher outboxPublisher;
    private final MeterRegistry meterRegistry;
    private final OrderProperties orderProperties;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orderRepository,
                                 OrderStatusHistoryRepository statusHistoryRepository,
                                 OutboxService outboxService,
                                 AuditLogService auditLogService,
                                 @Nullable MeterRegistry meterRegistry,
                                 @Nullable OrderProperties orderProperties,
                                 @Nullable Clock clock) {
        this.orderRepository = orderRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxPublisher = new OrderOutboxPublisher(outboxService, auditLogService);
        this.meterRegistry = meterRegistry;
        this.orderProperties = orderProperties != null ? orderProperties : new OrderProperties();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    Order lockOrder(UUID orderId) {
        long start = System.nanoTime();
        try {
            return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        } finally {
            recordLockContention(start);
        }
    }

    private Order lockOrderForUser(UUID orderId, UUID userId) {
        long start = System.nanoTime();
        try {
            return orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        } finally {
            recordLockContention(start);
        }
    }

    private void recordLockContention(long startNano) {
        if (meterRegistry != null) {
            meterRegistry.timer("order.lock.contention.duration")
                .record(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, UUID requesterId, boolean isAdmin) {
        Order order = fetchOrder(orderId, requesterId, isAdmin);
        List<OrderStatusHistory> history = statusHistoryRepository.findByOrder_IdOrderByCreatedAtAsc(orderId);
        return OrderMapper.toResponse(order, history);
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse getOrderStatus(UUID orderId, UUID requesterId, boolean isAdmin) {
        Order order = fetchOrder(orderId, requesterId, isAdmin);
        List<OrderStatusHistory> history = statusHistoryRepository.findByOrder_IdOrderByCreatedAtAsc(orderId);
        return OrderMapper.toStatusResponse(order, history);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, PageableUtils.sanitize(pageable)).map(OrderMapper::toSummary);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void cancelOrderByUser(UUID orderId, UUID userId, String reason) {
        Order order = lockOrderForUser(orderId, userId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PLACED) {
            throw new InvalidOrderStateException("User cancellation is only allowed before packing starts.");
        }
        OrderStateMachine.validate(order.getStatus(), OrderStatus.CANCELLED);
        OrderStatus previous = order.getStatus();
        completeCancellation(order, previous, reason, "User cancelled order", "user:" + userId, userId, false, false);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void cancelOrder(UUID orderId, String reason, String changedBy, UUID actorId) {
        Order order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        OrderStateMachine.validate(order.getStatus(), OrderStatus.CANCELLED);
        OrderStatus previous = order.getStatus();
        completeCancellation(order, previous, reason, "Order cancelled", changedBy, actorId, false, false);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note, UUID actorId) {
        Order order = lockOrder(orderId);
        applyOrderStatusTransition(order, newStatus, changedBy, note, actorId);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void advanceLifecycleFromFulfillment(UUID orderId, OrderStatus targetStatus, String changedBy, String note) {
        Order order = lockOrder(orderId);
        OrderStatus currentStatus = order.getStatus();
        if (currentStatus.isTerminal()) {
            logger.info("Ignoring fulfillment event for order {} in terminal state {}", orderId, currentStatus);
            return;
        }
        if (currentStatus == OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                "Cannot apply fulfillment event " + targetStatus + " while order is PENDING");
        }
        if (targetStatus == OrderStatus.FAILED) {
            applyOrderStatusTransition(order, OrderStatus.FAILED, changedBy, note, null);
            return;
        }
        if (hasReachedLifecycleStep(currentStatus, targetStatus)) {
            logger.debug("Ignoring stale fulfillment event for order {} at {} (target {})",
                orderId, currentStatus, targetStatus);
            return;
        }
        for (OrderStatus nextStatus : fulfillmentProgressionFor(targetStatus)) {
            if (hasReachedLifecycleStep(currentStatus, nextStatus)) {
                continue;
            }
            applyOrderStatusTransition(order, nextStatus, changedBy, note, null);
            currentStatus = nextStatus;
        }
    }

    private void applyOrderStatusTransition(Order lockedOrder, OrderStatus newStatus,
                                            String changedBy, String note, UUID actorId) {
        if (lockedOrder.getStatus() == newStatus) {
            return;
        }
        if (lockedOrder.getStatus().isTerminal()) {
            throw new IllegalOrderStateTransitionException(lockedOrder.getStatus(), newStatus);
        }
        OrderStateMachine.validate(lockedOrder.getStatus(), newStatus);
        OrderStatus previous = lockedOrder.getStatus();
        Instant now = Instant.now(clock);
        if (newStatus == OrderStatus.PLACED && lockedOrder.getSlaDeadlineAt() == null) {
            int slaMinutes = Math.max(1, orderProperties.getSla().getPromisedDeliveryMinutes());
            lockedOrder.setSlaDeadlineAt(now.plus(Duration.ofMinutes(slaMinutes)));
        }
        if (newStatus == OrderStatus.DELIVERED && lockedOrder.getDeliveredAt() == null) {
            lockedOrder.setDeliveredAt(now);
        }
        boolean slaNewlyBreached = isSlaTrackedStatus(newStatus) && lockedOrder.recordSlaBreachIfDue(now);
        if (newStatus == OrderStatus.CANCELLED) {
            String cancelReason = note != null ? note : "Order cancelled";
            completeCancellation(lockedOrder, previous, cancelReason, "Order cancelled", changedBy, actorId, true, slaNewlyBreached);
            return;
        }
        lockedOrder.setStatus(newStatus);
        Order saved = orderRepository.save(lockedOrder);
        recordStatusChange(saved, previous, newStatus, changedBy, note);
        outboxPublisher.publishStatusTransitionEffects(saved, previous, newStatus, note, actorId, now, slaNewlyBreached);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void recordFulfillmentAuditNote(UUID orderId, String changedBy, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        Order order = lockOrder(orderId);
        List<OrderStatusHistory> existingHistory = statusHistoryRepository.findByOrder_IdOrderByCreatedAtAsc(orderId);
        if (existingHistory != null && existingHistory.stream().anyMatch(entry -> note.equals(entry.getNote()))) {
            logger.debug("Ignoring duplicate fulfillment audit note for order {} note={}", orderId, note);
            return;
        }
        OrderStatus currentStatus = order.getStatus();
        recordStatusChange(order, currentStatus, currentStatus, changedBy, note);
    }

    @Transactional
    public int checkAndRecordSlaBreaches() {
        Instant now = Instant.now(clock);
        List<Order> candidates = orderRepository.findUnrecordedSlaBreaches(
            now,
            List.of(OrderStatus.PLACED, OrderStatus.PACKING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY),
            PageRequest.of(0, 200));
        int breachedCount = 0;
        for (Order candidate : candidates) {
            if (candidate.recordSlaBreachIfDue(now)) {
                orderRepository.save(candidate);
                outboxPublisher.publishSlaBreachedEvent(candidate);
                breachedCount++;
            }
        }
        return breachedCount;
    }

    private void completeCancellation(Order order,
                                      OrderStatus previous,
                                      String reason,
                                      String defaultDescription,
                                      String changedBy,
                                      UUID actorId,
                                      boolean emitStatusChanged,
                                      boolean slaNewlyBreached) {
        String effectiveReason = reason != null ? reason : defaultDescription;
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, OrderStatus.CANCELLED, changedBy, reason);
        outboxPublisher.publishCancelled(
            saved, previous, reason, effectiveReason, actorId, emitStatusChanged, slaNewlyBreached);
    }

    private void recordStatusChange(Order order, OrderStatus from, OrderStatus to,
                                    String changedBy, String note) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setChangedBy(changedBy);
        history.setNote(note);
        statusHistoryRepository.save(history);
    }

    private boolean hasReachedLifecycleStep(OrderStatus current, OrderStatus target) {
        return lifecycleRank(current) >= lifecycleRank(target);
    }

    private int lifecycleRank(OrderStatus status) {
        return switch (status) {
            case PLACED -> 1;
            case PACKING -> 2;
            case PACKED -> 3;
            case OUT_FOR_DELIVERY -> 4;
            case DELIVERED -> 5;
            default -> Integer.MIN_VALUE;
        };
    }

    private List<OrderStatus> fulfillmentProgressionFor(OrderStatus targetStatus) {
        return switch (targetStatus) {
            case PACKED -> List.of(OrderStatus.PACKING, OrderStatus.PACKED);
            case OUT_FOR_DELIVERY -> List.of(OrderStatus.PACKING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY);
            case DELIVERED -> List.of(
                OrderStatus.PACKING,
                OrderStatus.PACKED,
                OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED);
            case FAILED -> List.of(OrderStatus.FAILED);
            default -> throw new IllegalArgumentException("Unsupported fulfillment target status: " + targetStatus);
        };
    }

    private Order fetchOrder(UUID orderId, UUID requesterId, boolean isAdmin) {
        if (isAdmin) {
            return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        }
        if (requesterId == null) {
            throw new OrderNotFoundException(orderId);
        }
        return orderRepository.findByIdAndUserId(orderId, requesterId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private boolean isSlaTrackedStatus(OrderStatus status) {
        return status == OrderStatus.PACKING
            || status == OrderStatus.PACKED
            || status == OrderStatus.OUT_FOR_DELIVERY
            || status == OrderStatus.DELIVERED;
    }
}
