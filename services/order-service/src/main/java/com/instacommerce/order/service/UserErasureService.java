package com.instacommerce.order.service;

import com.instacommerce.order.repository.OrderRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserErasureService {
    private static final UUID ANON_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;

    public UserErasureService(OrderRepository orderRepository, AuditLogService auditLogService) {
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void anonymizeUser(UUID userId, Instant erasedAt) {
        int updated = orderRepository.anonymizeByUserId(userId, ANON_USER_ID);
        auditLogService.log(userId,
            "USER_ERASURE_APPLIED",
            "Order",
            userId.toString(),
            Map.of("updated", updated, "erasedAt", erasedAt, "placeholderUserId", ANON_USER_ID));
    }
}
