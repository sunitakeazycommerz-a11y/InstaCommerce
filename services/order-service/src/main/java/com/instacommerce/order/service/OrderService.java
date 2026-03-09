package com.instacommerce.order.service;

import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.model.OrderStatusHistory;
import com.instacommerce.order.domain.statemachine.OrderStateMachine;
import com.instacommerce.order.dto.mapper.OrderMapper;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.exception.OrderNotFoundException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository statusHistoryRepository,
                        OutboxService outboxService,
                        AuditLogService auditLogService) {
        this.orderRepository = orderRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public String createOrder(CreateOrderCommand command) {
        Optional<Order> existing = orderRepository.findByIdempotencyKey(command.getIdempotencyKey());
        if (existing.isPresent()) {
            return existing.get().getId().toString();
        }
        Order order = new Order();
        order.setUserId(command.getUserId());
        order.setStoreId(command.getStoreId());
        order.setStatus(OrderStatus.PENDING);
        order.setSubtotalCents(command.getSubtotalCents());
        order.setDiscountCents(command.getDiscountCents());
        order.setTotalCents(command.getTotalCents());
        order.setCurrency(command.getCurrency());
        order.setCouponCode(command.getCouponCode());
        order.setReservationId(command.getReservationId());
        order.setPaymentId(command.getPaymentId());
        order.setIdempotencyKey(command.getIdempotencyKey());
        order.setDeliveryAddress(command.getDeliveryAddress());

        List<OrderItem> items = new ArrayList<>();
        for (CartItem item : command.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(item.productId());
            orderItem.setProductName(item.productName());
            orderItem.setProductSku(item.productSku());
            orderItem.setQuantity(item.quantity());
            orderItem.setUnitPriceCents(item.unitPriceCents());
            orderItem.setLineTotalCents((long) item.quantity() * item.unitPriceCents());
            items.add(orderItem);
        }
        order.setItems(items);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, null, OrderStatus.PENDING, "system", "Order created");
        outboxService.publish("Order", saved.getId().toString(), "OrderCreated",
            Map.of("orderId", saved.getId(), "userId", saved.getUserId(), "status", saved.getStatus().name()));
        return saved.getId().toString();
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
        Pageable sanitized = sanitize(pageable);
        return orderRepository.findByUserId(userId, sanitized).map(OrderMapper::toSummary);
    }

    @Transactional
    public void cancelOrder(UUID orderId, String reason, String changedBy) {
        cancelOrder(orderId, reason, changedBy, null);
    }

    @Transactional
    public void cancelOrderByUser(UUID orderId, UUID userId, String reason) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PLACED) {
            throw new InvalidOrderStateException("User cancellation is only allowed before packing starts.");
        }
        OrderStateMachine.validate(order.getStatus(), OrderStatus.CANCELLED);
        OrderStatus previous = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, OrderStatus.CANCELLED, "user:" + userId, reason);
        auditLogService.log(userId,
            "ORDER_CANCELLED",
            "Order",
            saved.getId().toString(),
            Map.of("orderUserId", saved.getUserId(), "reason", reason));
        outboxService.publish("Order", saved.getId().toString(), "OrderCancelled",
            Map.of("orderId", saved.getId(), "userId", saved.getUserId(),
                "paymentId", saved.getPaymentId() != null ? saved.getPaymentId() : "",
                "totalCents", saved.getTotalCents(), "currency", saved.getCurrency(),
                "reason", reason));
        // Payment refund should be handled by a consumer of the OrderCancelled event
    }

    @Transactional
    public void cancelOrder(UUID orderId, String reason, String changedBy, UUID actorId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        OrderStateMachine.validate(order.getStatus(), OrderStatus.CANCELLED);
        OrderStatus previous = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, OrderStatus.CANCELLED, changedBy, reason);
        auditLogService.log(actorId,
            "ORDER_CANCELLED",
            "Order",
            saved.getId().toString(),
            Map.of("orderUserId", saved.getUserId(), "reason", reason));
        outboxService.publish("Order", saved.getId().toString(), "OrderCancelled",
            Map.of("orderId", saved.getId(), "userId", saved.getUserId(),
                "paymentId", saved.getPaymentId() != null ? saved.getPaymentId() : "",
                "totalCents", saved.getTotalCents(), "currency", saved.getCurrency(),
                "reason", reason));
        // Payment refund should be handled by a consumer of the OrderCancelled event
        // (e.g., payment-service listens and issues void/refund via PSP)
    }

    @Transactional
    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note) {
        updateOrderStatus(orderId, newStatus, changedBy, note, null);
    }

    @Transactional
    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note, UUID actorId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == newStatus) {
            return;
        }
        OrderStateMachine.validate(order.getStatus(), newStatus);
        OrderStatus previous = order.getStatus();
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, newStatus, changedBy, note);
        if (newStatus == OrderStatus.PLACED) {
            auditLogService.log(saved.getUserId(),
                "ORDER_PLACED",
                "Order",
                saved.getId().toString(),
                Map.of("totalCents", saved.getTotalCents(), "currency", saved.getCurrency()));
        } else if (newStatus == OrderStatus.CANCELLED) {
            auditLogService.log(actorId,
                "ORDER_CANCELLED",
                "Order",
                saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", note));
        }
        outboxService.publish("Order", saved.getId().toString(), "OrderStatusChanged",
            Map.of("orderId", saved.getId(), "from", previous.name(), "to", newStatus.name()));
        if (newStatus == OrderStatus.PLACED) {
            outboxService.publish("Order", saved.getId().toString(), "OrderPlaced", buildOrderPlacedPayload(saved));
        }
    }

    @Transactional
    public void advanceLifecycleFromFulfillment(UUID orderId, OrderStatus targetStatus, String changedBy, String note) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus currentStatus = order.getStatus();
        if (currentStatus == OrderStatus.CANCELLED
            || currentStatus == OrderStatus.FAILED
            || currentStatus == OrderStatus.DELIVERED) {
            logger.info("Ignoring fulfillment event for order {} in terminal state {}", orderId, currentStatus);
            return;
        }
        if (currentStatus == OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                "Cannot apply fulfillment event " + targetStatus + " while order is PENDING");
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
            updateOrderStatus(orderId, nextStatus, changedBy, note);
            currentStatus = nextStatus;
        }
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

    private Map<String, Object> buildOrderPlacedPayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("userId", order.getUserId());
        payload.put("storeId", order.getStoreId());
        payload.put("paymentId", order.getPaymentId());
        payload.put("status", order.getStatus().name());
        payload.put("subtotalCents", order.getSubtotalCents());
        payload.put("discountCents", order.getDiscountCents());
        payload.put("totalCents", order.getTotalCents());
        payload.put("currency", order.getCurrency());
        payload.put("deliveryAddress", order.getDeliveryAddress());
        payload.put("placedAt", order.getUpdatedAt());
        List<OrderItem> items = order.getItems() == null ? List.of() : order.getItems();
        payload.put("items", items.stream()
            .map(item -> {
                Map<String, Object> itemPayload = new LinkedHashMap<>();
                itemPayload.put("productId", item.getProductId());
                itemPayload.put("productName", item.getProductName());
                itemPayload.put("sku", item.getProductSku());
                itemPayload.put("quantity", item.getQuantity());
                itemPayload.put("unitPriceCents", item.getUnitPriceCents());
                itemPayload.put("lineTotalCents", item.getLineTotalCents());
                return itemPayload;
            })
            .toList());
        return payload;
    }

    private Pageable sanitize(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
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
}
