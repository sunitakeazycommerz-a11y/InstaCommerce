package com.instacommerce.order.service;

import com.instacommerce.contracts.orders.CancellationReason;
import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderRefundCalculator;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.model.OrderStatusHistory;
import com.instacommerce.order.domain.statemachine.OrderStateMachine;
import com.instacommerce.order.dto.mapper.OrderMapper;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.exception.OrderAmendmentForbiddenException;
import com.instacommerce.order.exception.OrderNotFoundException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;
    private final PricingQuoteClient pricingQuoteClient;
    private final MeterRegistry meterRegistry;
    private final OrderProperties orderProperties;
    private final Clock clock;
    private TransactionOperations transactionOperations = TransactionOperations.withoutTransaction();

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository statusHistoryRepository,
                        OutboxService outboxService,
                        AuditLogService auditLogService,
                        PricingQuoteClient pricingQuoteClient) {
        this(orderRepository, statusHistoryRepository, outboxService, auditLogService,
            pricingQuoteClient, null, new OrderProperties(), Clock.systemUTC());
    }

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository statusHistoryRepository,
                        OutboxService outboxService,
                        AuditLogService auditLogService,
                        PricingQuoteClient pricingQuoteClient,
                        @Nullable MeterRegistry meterRegistry) {
        this(orderRepository, statusHistoryRepository, outboxService, auditLogService,
            pricingQuoteClient, meterRegistry, new OrderProperties(), Clock.systemUTC());
    }

    @Autowired
    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository statusHistoryRepository,
                        OutboxService outboxService,
                        AuditLogService auditLogService,
                        PricingQuoteClient pricingQuoteClient,
                        @Nullable MeterRegistry meterRegistry,
                        @Nullable OrderProperties orderProperties) {
        this(orderRepository, statusHistoryRepository, outboxService, auditLogService,
            pricingQuoteClient, meterRegistry,
            orderProperties != null ? orderProperties : new OrderProperties(), Clock.systemUTC());
    }

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository statusHistoryRepository,
                        OutboxService outboxService,
                        AuditLogService auditLogService,
                        PricingQuoteClient pricingQuoteClient,
                        @Nullable MeterRegistry meterRegistry,
                        OrderProperties orderProperties,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
        this.pricingQuoteClient = pricingQuoteClient;
        this.meterRegistry = meterRegistry;
        this.orderProperties = orderProperties != null ? orderProperties : new OrderProperties();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionOperations = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    private Order lockOrder(UUID orderId) {
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

    public String createOrder(CreateOrderCommand command) {
        String normalizedKey = normalizeIdempotencyKey(command.getIdempotencyKey());
        Optional<Order> existing = orderRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return existing.get().getId().toString();
        }
        validateCreateOrderCommand(command);
        if (command.getQuoteId() != null && command.getQuoteToken() != null) {
            boolean quoteValid = pricingQuoteClient.validateQuote(
                command.getQuoteId(), command.getQuoteToken(),
                command.getTotalCents(), command.getSubtotalCents(), command.getDiscountCents());
            if (!quoteValid) {
                throw new ApiException(HttpStatus.CONFLICT, "QUOTE_VALIDATION_FAILED",
                    "Price quote validation failed for quoteId=" + command.getQuoteId());
            }
        }
        try {
            return executeInTransaction(() -> persistOrder(command, normalizedKey));
        } catch (DataIntegrityViolationException ex) {
            return orderRepository.findByIdempotencyKey(normalizedKey)
                .map(order -> order.getId().toString())
                .orElseThrow(() -> ex);
        }
    }

    private void validateCreateOrderCommand(CreateOrderCommand command) {
        if (command.getTotalCents() < 0
                || command.getSubtotalCents() < 0
                || command.getDiscountCents() < 0
                || command.getDeliveryFeeCents() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Order monetary amounts must be non-negative: subtotal=" + command.getSubtotalCents()
                + ", discount=" + command.getDiscountCents()
                + ", deliveryFee=" + command.getDeliveryFeeCents()
                + ", total=" + command.getTotalCents());
        }
        long netItemsCents = Math.max(0L, command.getSubtotalCents() - command.getDiscountCents());
        long expectedTotal = netItemsCents + command.getDeliveryFeeCents();
        if (expectedTotal != command.getTotalCents()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Order total must equal max(0, subtotal - discount) + deliveryFee: subtotal="
                + command.getSubtotalCents()
                + ", discount=" + command.getDiscountCents()
                + ", deliveryFee=" + command.getDeliveryFeeCents()
                + ", total=" + command.getTotalCents());
        }
        if (command.getDiscountCents() > 0 || (command.getCouponCode() != null && !command.getCouponCode().isBlank())) {
            if (command.getQuoteId() == null || command.getQuoteToken() == null || command.getQuoteToken().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DISCOUNT_REQUIRES_QUOTE",
                    "Orders with discounts or coupons must include a validated quoteId and quoteToken from pricing-service");
            }
        }
    }

    private String persistOrder(CreateOrderCommand command, String normalizedKey) {
        Optional<Order> existing = orderRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return existing.get().getId().toString();
        }
        Order order = new Order();
        if (command.getOrderId() != null) {
            order.setId(command.getOrderId());
        }
        order.setUserId(command.getUserId());
        order.setStoreId(command.getStoreId());
        order.setStatus(OrderStatus.PENDING);
        order.setSubtotalCents(command.getSubtotalCents());
        order.setDiscountCents(command.getDiscountCents());
        order.setTotalCents(command.getTotalCents());
        order.setCurrency(command.getCurrency() != null && !command.getCurrency().isBlank() ? command.getCurrency() : "INR");
        order.setCouponCode(command.getCouponCode());
        order.setReservationId(command.getReservationId());
        order.setPaymentId(command.getPaymentId());
        order.setIdempotencyKey(normalizedKey);
        order.setDeliveryAddress(command.getDeliveryAddress());
        order.setDropoffLat(command.getDropoffLat());
        order.setDropoffLng(command.getDropoffLng());
        if (command.getQuoteId() != null) {
            order.setQuoteId(command.getQuoteId());
        }

        List<OrderItem> items = new ArrayList<>();
        for (CartItem item : command.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(item.productId());
            orderItem.setProductName(item.effectiveProductName());
            orderItem.setProductSku(item.effectiveProductSku());
            orderItem.setQuantity(item.quantity());
            orderItem.setUnitPriceCents(item.unitPriceCents());
            orderItem.setLineTotalCents(item.effectiveLineTotalCents());
            items.add(orderItem);
        }
        order.setItems(items);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, null, OrderStatus.PENDING, "system", "Order created");
        outboxService.publish("Order", saved.getId().toString(), "OrderCreated",
            Map.of("orderId", saved.getId(), "userId", saved.getUserId(), "status", saved.getStatus().name()));
        return saved.getId().toString();
    }

    private String executeInTransaction(Supplier<String> action) {
        return transactionOperations.execute(status -> action.get());
    }

    static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (rawKey.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            return rawKey;
        }
        return sha256Hex(rawKey);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
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
        Pageable sanitized = sanitize(pageable);
        return orderRepository.findByUserId(userId, sanitized).map(OrderMapper::toSummary);
    }

    @Transactional
    public void cancelOrder(UUID orderId, String reason, String changedBy) {
        cancelOrder(orderId, reason, changedBy, null);
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
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, OrderStatus.CANCELLED, "user:" + userId, reason);
        auditLogService.log(userId,
            "ORDER_CANCELLED",
            "Order",
            saved.getId().toString(),
            Map.of("orderUserId", saved.getUserId(), "reason", reason));
        Map<String, Object> userCancelPayload = new LinkedHashMap<>();
        userCancelPayload.put("orderId", saved.getId());
        userCancelPayload.put("userId", saved.getUserId());
        userCancelPayload.put("paymentId", saved.getPaymentId());
        userCancelPayload.put("totalCents", saved.getTotalCents());
        userCancelPayload.put("currency", saved.getCurrency());
        userCancelPayload.put("reason", reason);
        userCancelPayload.put("reasonCode", resolveReasonCode(reason, CancellationReason.CUSTOMER_REQUEST));
        userCancelPayload.put("reasonDescription", reason != null ? reason : "User cancelled order");
        outboxService.publish("Order", saved.getId().toString(), "OrderCancelled", userCancelPayload);
        // Payment refund should be handled by a consumer of the OrderCancelled event
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
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        recordStatusChange(saved, previous, OrderStatus.CANCELLED, changedBy, reason);
        auditLogService.log(actorId,
            "ORDER_CANCELLED",
            "Order",
            saved.getId().toString(),
            Map.of("orderUserId", saved.getUserId(), "reason", reason));
        Map<String, Object> cancelPayload = new LinkedHashMap<>();
        cancelPayload.put("orderId", saved.getId());
        cancelPayload.put("userId", saved.getUserId());
        cancelPayload.put("paymentId", saved.getPaymentId());
        cancelPayload.put("totalCents", saved.getTotalCents());
        cancelPayload.put("currency", saved.getCurrency());
        cancelPayload.put("reason", reason);
        cancelPayload.put("reasonCode", resolveReasonCode(reason, CancellationReason.CUSTOMER_REQUEST));
        cancelPayload.put("reasonDescription", reason != null ? reason : "Order cancelled");
        outboxService.publish("Order", saved.getId().toString(), "OrderCancelled", cancelPayload);
        // Payment refund should be handled by a consumer of the OrderCancelled event
        // (e.g., payment-service listens and issues void/refund via PSP)
    }

    @Transactional
    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note) {
        updateOrderStatus(orderId, newStatus, changedBy, note, null);
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note, UUID actorId) {
        Order order = lockOrder(orderId);
        if (order.getStatus() == newStatus) {
            return;
        }
        if (order.getStatus().isTerminal()) {
            throw new IllegalOrderStateTransitionException(order.getStatus(), newStatus);
        }
        OrderStateMachine.validate(order.getStatus(), newStatus);
        OrderStatus previous = order.getStatus();
        Instant now = Instant.now(clock);
        if (newStatus == OrderStatus.PLACED && order.getSlaDeadlineAt() == null) {
            int slaMinutes = Math.max(1, orderProperties.getSla().getPromisedDeliveryMinutes());
            order.setSlaDeadlineAt(now.plus(Duration.ofMinutes(slaMinutes)));
        }
        if (newStatus == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(now);
        }
        boolean slaNewlyBreached = isSlaTrackedStatus(newStatus) && order.recordSlaBreachIfDue(now);
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        if (saved == null) {
            saved = order;
        }
        recordStatusChange(saved, previous, newStatus, changedBy, note);
        if (newStatus == OrderStatus.PLACED) {
            auditLogService.log(saved.getUserId(),
                "ORDER_PLACED",
                "Order",
                saved.getId().toString(),
                Map.of("totalCents", saved.getTotalCents(), "currency", saved.getCurrency()));
        } else if (newStatus == OrderStatus.CANCELLED) {
            String cancelReason = note != null ? note : "Order cancelled";
            saved.setCancellationReason(cancelReason);
            auditLogService.log(actorId,
                "ORDER_CANCELLED",
                "Order",
                saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", cancelReason));
            String reasonCode = resolveReasonCode(cancelReason, CancellationReason.CUSTOMER_REQUEST);
            Map<String, Object> cancelPayload = new LinkedHashMap<>();
            cancelPayload.put("orderId", saved.getId());
            cancelPayload.put("userId", saved.getUserId());
            cancelPayload.put("paymentId", saved.getPaymentId());
            cancelPayload.put("totalCents", saved.getTotalCents());
            cancelPayload.put("currency", saved.getCurrency());
            cancelPayload.put("reason", cancelReason);
            cancelPayload.put("reasonCode", reasonCode);
            cancelPayload.put("reasonDescription", cancelReason);
            outboxService.publish("Order", saved.getId().toString(), "OrderCancelled", cancelPayload);
        } else if (newStatus == OrderStatus.FAILED) {
            auditLogService.log(actorId,
                "ORDER_FAILED",
                "Order",
                saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", note != null ? note : ""));
        } else if (newStatus == OrderStatus.REFUNDED) {
            String refundReason = note != null ? note : "Order refunded";
            auditLogService.log(actorId,
                "ORDER_REFUNDED",
                "Order",
                saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", refundReason));
        }
        outboxService.publish("Order", saved.getId().toString(), "OrderStatusChanged",
            Map.of("orderId", saved.getId(), "from", previous.name(), "to", newStatus.name()));
        if (slaNewlyBreached) {
            publishSlaBreachedEvent(saved);
        }
        if (newStatus == OrderStatus.PLACED) {
            outboxService.publish("Order", saved.getId().toString(), "OrderPlaced", buildOrderPlacedPayload(saved));
        } else if (newStatus == OrderStatus.FAILED) {
            Map<String, Object> failedPayload = new LinkedHashMap<>();
            failedPayload.put("orderId", saved.getId());
            failedPayload.put("userId", saved.getUserId());
            failedPayload.put("paymentId", saved.getPaymentId());
            failedPayload.put("reason", note != null ? note : "Fulfillment failure");
            outboxService.publish("Order", saved.getId().toString(), "OrderFailed", failedPayload);
        } else if (newStatus == OrderStatus.REFUNDED) {
            Map<String, Object> refundedPayload = new LinkedHashMap<>();
            refundedPayload.put("orderId", saved.getId());
            refundedPayload.put("userId", saved.getUserId());
            refundedPayload.put("paymentId", saved.getPaymentId());
            refundedPayload.put("totalCents", saved.getTotalCents());
            refundedPayload.put("currency", saved.getCurrency());
            refundedPayload.put("reason", note != null ? note : "Order refunded");
            outboxService.publish("Order", saved.getId().toString(), "OrderRefunded", refundedPayload);
        } else if (newStatus == OrderStatus.DELIVERED) {
            Map<String, Object> deliveredPayload = new LinkedHashMap<>();
            deliveredPayload.put("orderId", saved.getId().toString());
            deliveredPayload.put("userId", saved.getUserId().toString());
            deliveredPayload.put("paymentId", saved.getPaymentId() != null ? saved.getPaymentId().toString() : null);
            deliveredPayload.put("totalCents", saved.getTotalCents());
            deliveredPayload.put("currency", saved.getCurrency());
            deliveredPayload.put("deliveredAt",
                (saved.getDeliveredAt() != null ? saved.getDeliveredAt() : now).toString());
            deliveredPayload.put("slaBreached", saved.isSlaBreached());
            if (saved.getSlaDeadlineAt() != null) {
                deliveredPayload.put("slaDeadlineAt", saved.getSlaDeadlineAt().toString());
            }
            if (saved.getSlaBreachedAt() != null) {
                deliveredPayload.put("slaBreachedAt", saved.getSlaBreachedAt().toString());
            }
            outboxService.publish("Order", saved.getId().toString(), "OrderDelivered", deliveredPayload);
        }
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
            updateOrderStatus(orderId, OrderStatus.FAILED, changedBy, note);
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
        if (order.getDropoffLat() != null) {
            payload.put("dropoffLat", order.getDropoffLat());
        }
        if (order.getDropoffLng() != null) {
            payload.put("dropoffLng", order.getDropoffLng());
        }
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

    private String resolveReasonCode(String reason, CancellationReason defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason.name();
        }
        String upper = reason.toUpperCase(Locale.ROOT);
        for (CancellationReason code : CancellationReason.values()) {
            if (upper.contains(code.name())) {
                return code.name();
            }
        }
        if (upper.contains("CUSTOMER") || upper.contains("USER") || upper.contains("MIND")) {
            return CancellationReason.CUSTOMER_REQUEST.name();
        }
        if (upper.contains("PAYMENT") || upper.contains("DECLINE") || upper.contains("CARD")) {
            return CancellationReason.PAYMENT_FAILED.name();
        }
        if (upper.contains("STOCK") || upper.contains("INVENTORY") || upper.contains("SHORTAGE")) {
            return CancellationReason.INVENTORY_SHORTAGE.name();
        }
        if (upper.contains("ETA") || upper.contains("DELAY") || upper.contains("LATE")) {
            return CancellationReason.ETA_UNACCEPTABLE.name();
        }
        if (upper.contains("FRAUD") || upper.contains("RISK")) {
            return CancellationReason.FRAUD_SUSPECTED.name();
        }
        return defaultReason.name();
    }

    @Transactional
    public void validateOrderAmendmentWindow(UUID orderId) {
        validateOrderAmendmentWindow(orderId, null, true);
    }

    @Transactional
    public void validateOrderAmendmentWindow(UUID orderId, UUID requesterId, boolean bypassOwnershipCheck) {
        Order order = lockOrder(orderId);
        if (!bypassOwnershipCheck && (requesterId == null || !order.getUserId().equals(requesterId))) {
            throw new AccessDeniedException("Cannot amend another user's order");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PLACED) {
            throw new OrderAmendmentForbiddenException(
                "Order amendment rejected: modifications only permitted prior to packing. Current status: " + order.getStatus());
        }
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void applyOrderModification(UUID orderId, UUID productId, int missingQty, long refundCents) {
        applyOrderModification(new FulfillmentOrderEvent(orderId, productId, missingQty, refundCents, null, null, null));
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void applyOrderModification(FulfillmentOrderEvent event) {
        UUID orderId = Objects.requireNonNull(event.orderId(), "orderId must not be null");
        Order order = lockOrder(orderId);
        if (order.getStatus().isTerminal()) {
            logger.warn("Ignoring OrderModified event for order {} in terminal state {}", orderId, order.getStatus());
            return;
        }
        if (event.newVersion() != null && order.getVersion() >= event.newVersion()) {
            logger.info("Ignoring idempotent/stale OrderModified event for order {} currentVersion={} eventVersion={}",
                orderId, order.getVersion(), event.newVersion());
            return;
        }

        List<FulfillmentOrderEvent.OrderItemDelta> deltas = new ArrayList<>();
        if (event.itemDeltas() != null && !event.itemDeltas().isEmpty()) {
            deltas.addAll(event.itemDeltas());
        } else if (event.productId() != null) {
            int deltaQty = -(event.missingQty() != null && event.missingQty() > 0 ? event.missingQty() : 1);
            long deltaPrice = event.priceDeltaCents() != null
                ? event.priceDeltaCents()
                : (event.refundCents() != null ? -event.refundCents() : 0L);
            deltas.add(new FulfillmentOrderEvent.OrderItemDelta(event.productId(), deltaQty, deltaPrice));
        }

        long computedGrossRemoved = 0L;
        for (FulfillmentOrderEvent.OrderItemDelta delta : deltas) {
            if (delta.productId() == null) {
                continue;
            }
            int qtyDelta = delta.quantityDelta() != null ? delta.quantityDelta() : 0;
            Iterator<OrderItem> it = order.getItems().iterator();
            while (it.hasNext()) {
                OrderItem item = it.next();
                if (delta.productId().equals(item.getProductId())) {
                    if (qtyDelta < 0) {
                        int removedQty = Math.min(item.getQuantity(), -qtyDelta);
                        computedGrossRemoved += computeItemGrossReduction(item, removedQty);
                    }
                    int updatedQty = item.getQuantity() + qtyDelta;
                    if (updatedQty <= 0) {
                        item.setPickedStatus("SHORT_PICKED");
                        it.remove();
                    } else {
                        item.setQuantity(updatedQty);
                        item.setLineTotalCents(Math.max(0L, (long) updatedQty * item.getUnitPriceCents()));
                        if (qtyDelta < 0) {
                            item.setPickedStatus("PARTIAL");
                        }
                    }
                    break;
                }
            }
        }

        long priceDeltaCents;
        long refundCents;
        boolean hasExplicitPriceAdjustment = event.priceDeltaCents() != null
            || (event.refundCents() != null && event.refundCents() > 0);
        if (hasExplicitPriceAdjustment) {
            long rawDelta = event.priceDeltaCents() != null
                ? event.priceDeltaCents()
                : -event.refundCents();
            if (rawDelta < 0) {
                long boundedRefund = Math.min(order.getTotalCents(), -rawDelta);
                priceDeltaCents = -boundedRefund;
                refundCents = boundedRefund;
            } else {
                priceDeltaCents = rawDelta;
                refundCents = 0L;
            }
            order.setTotalCents(Math.max(0L, order.getTotalCents() + priceDeltaCents));
            if (order.getSubtotalCents() > 0 && computedGrossRemoved > 0) {
                OrderRefundCalculator.PartialRefundBreakdown subtotalUpdate =
                    OrderRefundCalculator.calculateForRemovedGross(
                        order.getSubtotalCents(), order.getDiscountCents(), order.getTotalCents() + refundCents, computedGrossRemoved);
                order.setSubtotalCents(subtotalUpdate.updatedSubtotalCents());
                order.setDiscountCents(subtotalUpdate.updatedDiscountCents());
            }
        } else if (computedGrossRemoved > 0) {
            OrderRefundCalculator.PartialRefundBreakdown breakdown =
                OrderRefundCalculator.calculateForRemovedGross(
                    order.getSubtotalCents(),
                    order.getDiscountCents(),
                    order.getTotalCents(),
                    computedGrossRemoved);
            refundCents = breakdown.refundCents();
            priceDeltaCents = -refundCents;
            if (order.getSubtotalCents() > 0) {
                order.setSubtotalCents(breakdown.updatedSubtotalCents());
                order.setDiscountCents(breakdown.updatedDiscountCents());
            }
            order.setTotalCents(breakdown.updatedTotalCents());
        } else {
            priceDeltaCents = 0L;
            refundCents = 0L;
        }
        long newTotal = order.getTotalCents();
        orderRepository.save(order);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        if (event.productId() != null) {
            auditDetails.put("productId", event.productId().toString());
        }
        if (event.missingQty() != null) {
            auditDetails.put("missingQty", event.missingQty());
        }
        auditDetails.put("refundCents", refundCents);
        auditDetails.put("priceDeltaCents", priceDeltaCents);
        auditLogService.log(order.getUserId(),
            "ORDER_MODIFIED",
            "Order",
            orderId.toString(),
            auditDetails);

        Map<String, Object> modifiedPayload = new LinkedHashMap<>();
        modifiedPayload.put("orderId", orderId.toString());
        if (event.productId() != null) {
            modifiedPayload.put("productId", event.productId().toString());
        }
        if (event.missingQty() != null) {
            modifiedPayload.put("missingQty", event.missingQty());
        }
        modifiedPayload.put("refundCents", refundCents);
        modifiedPayload.put("priceDeltaCents", priceDeltaCents);
        modifiedPayload.put("newTotalCents", newTotal);
        outboxService.publish("Order", orderId.toString(), "OrderModified", modifiedPayload);

        if (priceDeltaCents < 0 && refundCents > 0) {
            publishPartialRefundEvent(order, refundCents, priceDeltaCents, "ORDER_MODIFIED_PARTIAL_REFUND");
        }
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public PartialCancellationResult cancelOrderItems(UUID orderId,
                                                      Map<UUID, Integer> itemQuantities,
                                                      String reason,
                                                      String changedBy,
                                                      UUID actorId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        if (itemQuantities == null || itemQuantities.isEmpty()) {
            throw new IllegalArgumentException("At least one item quantity must be specified for cancellation");
        }
        Order order = lockOrder(orderId);
        if (order.getStatus().isTerminal()) {
            throw new IllegalOrderStateTransitionException(order.getStatus(), OrderStatus.CANCELLED);
        }

        long grossRemovedCents = 0L;
        for (Map.Entry<UUID, Integer> entry : itemQuantities.entrySet()) {
            UUID productId = Objects.requireNonNull(entry.getKey(), "productId must not be null");
            Integer cancelQty = entry.getValue();
            if (cancelQty == null || cancelQty <= 0) {
                throw new IllegalArgumentException("Cancellation quantity must be positive for product " + productId);
            }
            OrderItem item = order.getItems().stream()
                .filter(candidate -> productId.equals(candidate.getProductId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product not found in order: " + productId));
            if (cancelQty > item.getQuantity()) {
                throw new IllegalArgumentException(
                    "Cannot cancel qty " + cancelQty + " for product " + productId
                        + " when order only has " + item.getQuantity());
            }
            grossRemovedCents += computeItemGrossReduction(item, cancelQty);
            int remainingQty = item.getQuantity() - cancelQty;
            if (remainingQty == 0) {
                order.getItems().remove(item);
            } else {
                item.setQuantity(remainingQty);
                item.setLineTotalCents((long) remainingQty * item.getUnitPriceCents());
            }
        }

        if (order.getItems().isEmpty()) {
            long fullRefundCents = order.getTotalCents();
            String effectiveReason = reason != null && !reason.isBlank() ? reason : "All order items cancelled";
            cancelOrder(orderId, effectiveReason, changedBy != null ? changedBy : "system", actorId);
            return new PartialCancellationResult(orderId, fullRefundCents, 0L, true);
        }

        OrderRefundCalculator.PartialRefundBreakdown breakdown =
            OrderRefundCalculator.calculateForRemovedGross(
                order.getSubtotalCents(),
                order.getDiscountCents(),
                order.getTotalCents(),
                grossRemovedCents);
        if (order.getSubtotalCents() > 0) {
            order.setSubtotalCents(breakdown.updatedSubtotalCents());
            order.setDiscountCents(breakdown.updatedDiscountCents());
        }
        order.setTotalCents(breakdown.updatedTotalCents());
        orderRepository.save(order);

        String effectiveReason = reason != null && !reason.isBlank() ? reason : "PARTIAL_ITEM_CANCELLATION";
        auditLogService.log(actorId != null ? actorId : order.getUserId(),
            "ORDER_PARTIALLY_CANCELLED",
            "Order",
            orderId.toString(),
            Map.of(
                "refundCents", breakdown.refundCents(),
                "remainingTotalCents", breakdown.updatedTotalCents(),
                "reason", effectiveReason));
        if (breakdown.refundCents() > 0) {
            publishPartialRefundEvent(order, breakdown.refundCents(), -breakdown.refundCents(), effectiveReason);
        }
        return new PartialCancellationResult(orderId, breakdown.refundCents(), breakdown.updatedTotalCents(), false);
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
                publishSlaBreachedEvent(candidate);
                breachedCount++;
            }
        }
        return breachedCount;
    }

    public record PartialCancellationResult(
        UUID orderId,
        long refundCents,
        long remainingTotalCents,
        boolean fullOrderCancelled
    ) {
    }

    private long computeItemGrossReduction(OrderItem item, int removedQty) {
        if (removedQty <= 0) {
            return 0L;
        }
        if (item.getUnitPriceCents() > 0) {
            return (long) removedQty * item.getUnitPriceCents();
        }
        if (item.getQuantity() > 0 && item.getLineTotalCents() > 0) {
            return (item.getLineTotalCents() * removedQty) / item.getQuantity();
        }
        return 0L;
    }

    private boolean isSlaTrackedStatus(OrderStatus status) {
        return status == OrderStatus.PACKING
            || status == OrderStatus.PACKED
            || status == OrderStatus.OUT_FOR_DELIVERY
            || status == OrderStatus.DELIVERED;
    }

    private void publishSlaBreachedEvent(Order order) {
        Instant deadline = order.getSlaDeadlineAt();
        Instant breachedAt = order.getSlaBreachedAt();
        long breachSeconds = (deadline != null && breachedAt != null)
            ? Math.max(0L, Duration.between(deadline, breachedAt).getSeconds())
            : 0L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("userId", order.getUserId().toString());
        payload.put("status", order.getStatus().name());
        if (deadline != null) {
            payload.put("slaDeadlineAt", deadline.toString());
        }
        if (breachedAt != null) {
            payload.put("slaBreachedAt", breachedAt.toString());
        }
        payload.put("breachSeconds", breachSeconds);
        outboxService.publish("Order", order.getId().toString(), "OrderSlaBreached", payload);
    }

    private void publishPartialRefundEvent(Order order, long refundCents, long priceDeltaCents, String reason) {
        Map<String, Object> refundPayload = new LinkedHashMap<>();
        refundPayload.put("orderId", order.getId().toString());
        if (order.getPaymentId() != null) {
            refundPayload.put("paymentId", order.getPaymentId().toString());
        }
        refundPayload.put("refundCents", refundCents);
        refundPayload.put("priceDeltaCents", priceDeltaCents);
        refundPayload.put("reason", reason);
        outboxService.publish("Order", order.getId().toString(), "OrderPartiallyRefunded", refundPayload);
    }
}
