package com.instacommerce.order.service;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.model.OrderStatusHistory;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderCreationService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OutboxService outboxService;
    private final PricingQuoteClient pricingQuoteClient;
    private final TransactionOperations transactionOperations;

    public OrderCreationService(OrderRepository orderRepository,
                                OrderStatusHistoryRepository statusHistoryRepository,
                                OutboxService outboxService,
                                PricingQuoteClient pricingQuoteClient,
                                PlatformTransactionManager transactionManager) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.statusHistoryRepository = Objects.requireNonNull(
            statusHistoryRepository, "statusHistoryRepository must not be null");
        this.outboxService = Objects.requireNonNull(outboxService, "outboxService must not be null");
        this.pricingQuoteClient = Objects.requireNonNull(pricingQuoteClient, "pricingQuoteClient must not be null");
        this.transactionOperations = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    public String createOrder(CreateOrderCommand command) {
        String normalizedKey = normalizeIdempotencyKey(command.getIdempotencyKey());
        Optional<Order> existing = orderRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return existing.get().getId().toString();
        }
        validateCreateOrderCommand(command);
        boolean hasQuote = command.getQuoteId() != null
            || (command.getQuoteToken() != null && !command.getQuoteToken().isBlank());
        if (hasQuote) {
            boolean quoteValid = pricingQuoteClient.validateQuote(
                command.getUserId(),
                command.getQuoteId(),
                command.getQuoteToken(),
                command.getTotalCents(),
                command.getSubtotalCents(),
                command.getDiscountCents());
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
        if (command.getItems() == null || command.getItems().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ORDER_ITEMS",
                "Order must contain at least one item");
        }
        if (command.getTotalCents() < 0
                || command.getSubtotalCents() < 0
                || command.getDiscountCents() < 0
                || command.getDeliveryFeeCents() < 0
                || command.getWalletAmountCents() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Order monetary amounts must be non-negative: subtotal=" + command.getSubtotalCents()
                + ", discount=" + command.getDiscountCents()
                + ", deliveryFee=" + command.getDeliveryFeeCents()
                + ", total=" + command.getTotalCents()
                + ", walletAmount=" + command.getWalletAmountCents());
        }
        long computedSubtotalCents = 0L;
        for (CartItem item : command.getItems()) {
            if (item == null || item.quantity() == null || item.quantity() <= 0
                    || item.unitPriceCents() == null || item.unitPriceCents() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_ITEM",
                    "Order item must have positive quantity and unitPriceCents");
            }
            long expectedLineTotal = Math.multiplyExact((long) item.quantity(), item.unitPriceCents());
            if (item.lineTotalCents() != null && item.lineTotalCents() != expectedLineTotal) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                    "Order item lineTotalCents must equal quantity * unitPriceCents for product=" + item.productId());
            }
            computedSubtotalCents = Math.addExact(computedSubtotalCents, expectedLineTotal);
        }
        if (computedSubtotalCents != command.getSubtotalCents()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Order subtotalCents must equal sum of item line totals: expected="
                + computedSubtotalCents + ", actual=" + command.getSubtotalCents());
        }
        if (command.getDiscountCents() > command.getSubtotalCents()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Discount cannot exceed order subtotal: discount=" + command.getDiscountCents()
                + ", subtotal=" + command.getSubtotalCents());
        }
        if (command.getWalletAmountCents() > command.getTotalCents()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TOTAL",
                "Wallet amount cannot exceed order total: walletAmount=" + command.getWalletAmountCents()
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
        boolean hasQuoteId = command.getQuoteId() != null;
        boolean hasQuoteToken = command.getQuoteToken() != null && !command.getQuoteToken().isBlank();
        if (command.getDiscountCents() > 0 || (command.getCouponCode() != null && !command.getCouponCode().isBlank())) {
            if (!hasQuoteId || !hasQuoteToken) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DISCOUNT_REQUIRES_QUOTE",
                    "Orders with discounts or coupons must include a validated quoteId and quoteToken from pricing-service");
            }
        } else if (hasQuoteId != hasQuoteToken) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE",
                "Both quoteId and quoteToken must be supplied together");
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
        order.setWalletAmountCents(command.getWalletAmountCents());
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
            orderItem.setColdChain(item.effectiveColdChain());
            orderItem.setFragile(item.effectiveFragile());
            orderItem.setStorageTemperature(item.storageTemperature());
            orderItem.setTareWeightGrams(item.effectiveTareWeightGrams());
            items.add(orderItem);
        }
        order.setItems(items);
        Order saved = orderRepository.save(order);

        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(saved);
        history.setFromStatus(null);
        history.setToStatus(OrderStatus.PENDING);
        history.setChangedBy("system");
        history.setNote("Order created");
        statusHistoryRepository.save(history);

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
}
