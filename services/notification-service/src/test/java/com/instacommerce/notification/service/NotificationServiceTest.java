package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.DeviceToken;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.metrics.NotificationMetrics;
import com.instacommerce.notification.infrastructure.retry.NotificationDlqPublisher;
import com.instacommerce.notification.infrastructure.retry.RetryableNotificationSender;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import com.instacommerce.notification.repository.NotificationLogRepository;
import com.instacommerce.notification.template.TemplateDefinition;
import com.instacommerce.notification.template.TemplateRegistry;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TemplateRegistry templateRegistry;

    @Mock
    private TemplateService templateService;

    @Mock
    private DeduplicationService deduplicationService;

    @Mock
    private NotificationLogRepository logRepository;

    @Mock
    private RetryableNotificationSender sender;

    @Mock
    private NotificationDlqPublisher dlqPublisher;

    @Mock
    private UserPreferenceService userPreferenceService;

    @Mock
    private UserDirectoryClient userDirectoryClient;

    @Mock
    private OrderLookupClient orderLookupClient;

    @Mock
    private NotificationMetrics metrics;

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private ChannelFallbackService channelFallbackService;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        notificationService = new NotificationService(
            templateRegistry,
            templateService,
            deduplicationService,
            logRepository,
            sender,
            dlqPublisher,
            userPreferenceService,
            userDirectoryClient,
            orderLookupClient,
            metrics,
            properties,
            deviceTokenRepository,
            channelFallbackService
        );
    }

    @Test
    @DisplayName("handleEvent propagates PreferenceLookupException so Kafka retry/DLQ can recover")
    void handleEventPropagatesPreferenceLookupException() {
        UUID userId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPlaced",
            "Order",
            UUID.randomUUID().toString(),
            Instant.now(),
            "1",
            "order-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString(), "email", "alice@example.com"))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 1L, "key", "{}");
        when(templateRegistry.resolve("OrderPlaced")).thenReturn(Optional.of(
            new TemplateDefinition("order_confirmed", List.of(NotificationChannel.EMAIL), "Order Confirmed")
        ));
        when(userDirectoryClient.findUser(userId)).thenReturn(Optional.empty());
        when(userPreferenceService.getPreferences(userId))
            .thenThrow(new PreferenceLookupException("identity-service timeout"));

        assertThatThrownBy(() -> notificationService.handleEvent(record, envelope))
            .isInstanceOf(PreferenceLookupException.class);

        verify(deduplicationService, never()).createLog(any(), any());
        verify(logRepository, never()).save(any());
    }

    @Test
    @DisplayName("handleEvent resolves active PUSH device token when payload omits recipient")
    void handleEventResolvesPushDeviceTokenFromRepository() {
        UUID userId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPacked",
            "order",
            "order-1",
            Instant.now(),
            "1",
            "order-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString()))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 2L, "key", "{}");
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setUserId(userId);
        deviceToken.setToken("fcm-device-token-987654");
        deviceToken.setPlatform("ANDROID");
        deviceToken.setActive(true);

        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(userId, false, false, false, false);
        when(templateRegistry.resolve("OrderPacked")).thenReturn(Optional.of(
            new TemplateDefinition("order_packed", List.of(NotificationChannel.PUSH), "Order Packed")
        ));
        when(userDirectoryClient.findUser(userId)).thenReturn(Optional.empty());
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.PUSH, false)).thenReturn(true);
        when(deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId))
            .thenReturn(Optional.of(deviceToken));
        NotificationLog logEntry = new NotificationLog();
        when(deduplicationService.createLog(any(NotificationRequest.class), anyString())).thenReturn(logEntry);
        when(templateService.render(eq(NotificationChannel.PUSH), eq("order_packed"), anyMap()))
            .thenReturn("Your order is packed");

        notificationService.handleEvent(record, envelope);

        ArgumentCaptor<NotificationRequest> requestCaptor = ArgumentCaptor.forClass(NotificationRequest.class);
        ArgumentCaptor<String> maskedCaptor = ArgumentCaptor.forClass(String.class);
        verify(deduplicationService).createLog(requestCaptor.capture(), maskedCaptor.capture());
        assertThat(requestCaptor.getValue().recipient()).isEqualTo("fcm-device-token-987654");
        assertThat(maskedCaptor.getValue()).doesNotContain("fcm-device-token-987654");
        verify(sender).send(eq(requestCaptor.getValue()), eq(logEntry), eq(null), eq("Your order is packed"));
    }

    @Test
    @DisplayName("handleEvent skips PUSH notification when user has no active device token")
    void handleEventSkipsPushWhenNoActiveDeviceToken() {
        UUID userId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPacked",
            "order",
            "order-2",
            Instant.now(),
            "1",
            "order-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString()))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 3L, "key", "{}");
        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(userId, false, false, false, false);
        when(templateRegistry.resolve("OrderPacked")).thenReturn(Optional.of(
            new TemplateDefinition("order_packed", List.of(NotificationChannel.PUSH), "Order Packed")
        ));
        when(userDirectoryClient.findUser(userId)).thenReturn(Optional.empty());
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.PUSH, false)).thenReturn(true);
        when(deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId))
            .thenReturn(Optional.empty());
        when(deduplicationService.createLog(any(NotificationRequest.class), eq("n/a")))
            .thenReturn(new NotificationLog());

        notificationService.handleEvent(record, envelope);

        ArgumentCaptor<NotificationLog> skippedCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(skippedCaptor.capture());
        assertThat(skippedCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(skippedCaptor.getValue().getLastError()).isEqualTo("Missing recipient");
        verify(sender, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Renders and dispatches ItemSubstituted, PaymentRefundFailed, TierUpgraded, and AccountLocked while stripping PII from template_context")
    void rendersAndDispatchesExpandedEventsWithoutPiiInTemplateContext() {
        NotificationProperties props = new NotificationProperties();
        DeduplicationService realDedup = new DeduplicationService(logRepository, objectMapper);
        TemplateRegistry realRegistry = new TemplateRegistry();
        TemplateService realTemplates = new TemplateService(new DefaultResourceLoader());
        NotificationService realService = new NotificationService(
            realRegistry, realTemplates, realDedup, logRepository, sender, dlqPublisher,
            userPreferenceService, userDirectoryClient, orderLookupClient, metrics, props,
            deviceTokenRepository, channelFallbackService);

        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID originalProductId = UUID.randomUUID();
        UUID substituteProductId = UUID.randomUUID();
        UserContact contact = new UserContact(userId, "asha@example.com", "+919876543210", "Asha", "en");
        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false);
        DeviceToken token = new DeviceToken();
        token.setUserId(userId);
        token.setToken("fcm-token-12345");
        token.setActive(true);

        when(userDirectoryClient.findUser(userId)).thenReturn(Optional.of(contact));
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(eq(prefs), any(NotificationChannel.class), eq(false)))
            .thenReturn(true);
        when(deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId))
            .thenReturn(Optional.of(token));
        when(orderLookupClient.findOrder(orderId))
            .thenReturn(Optional.of(new OrderSnapshot(orderId, userId, 12500L, "INR", "CONFIRMED", Instant.now())));
        when(logRepository.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));

        realService.handleEvent(
            new ConsumerRecord<>("fulfillment.events", 0, 1L, orderId.toString(), "{}"),
            new EventEnvelope(
                UUID.randomUUID().toString(), "ItemSubstituted", "Fulfillment", orderId.toString(),
                Instant.now(), "v1", "fulfillment-service", null,
                objectMapper.valueToTree(Map.of(
                    "orderId", orderId.toString(),
                    "storeId", UUID.randomUUID().toString(),
                    "originalProductId", originalProductId.toString(),
                    "substituteProductId", substituteProductId.toString(),
                    "substitutedQuantity", 2))));

        realService.handleEvent(
            new ConsumerRecord<>("payments.events", 0, 2L, orderId.toString(), "{}"),
            new EventEnvelope(
                UUID.randomUUID().toString(), "PaymentRefundFailed", "Payment", UUID.randomUUID().toString(),
                Instant.now(), "v1", "payment-service", null,
                objectMapper.valueToTree(Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", UUID.randomUUID().toString(),
                    "refundId", UUID.randomUUID().toString(),
                    "amountCents", 12500,
                    "currency", "INR",
                    "reason", "Gateway timeout",
                    "failureSource", "PSP"))));

        realService.handleEvent(
            new ConsumerRecord<>("wallet.events", 0, 3L, userId.toString(), "{}"),
            new EventEnvelope(
                UUID.randomUUID().toString(), "TierUpgraded", "Loyalty", UUID.randomUUID().toString(),
                Instant.now(), "v1", "wallet-loyalty-service", null,
                objectMapper.valueToTree(Map.of(
                    "accountId", UUID.randomUUID().toString(),
                    "userId", userId.toString(),
                    "previousTier", "SILVER",
                    "tier", "GOLD",
                    "points", 500,
                    "totalBalance", 1200,
                    "lifetimePoints", 2500))));

        realService.handleEvent(
            new ConsumerRecord<>("identity.events", 0, 4L, userId.toString(), "{}"),
            new EventEnvelope(
                UUID.randomUUID().toString(), "AccountLocked", "User", userId.toString(),
                Instant.now(), "v1", "identity-service", null,
                objectMapper.valueToTree(Map.of(
                    "userId", userId.toString(),
                    "email", "asha@example.com",
                    "lockedUntil", "2026-11-21T18:00:00Z",
                    "failedAttempts", 5,
                    "reason", "EXCESSIVE_FAILED_ATTEMPTS"))));

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, times(9)).send(any(), logCaptor.capture(), any(), bodyCaptor.capture());

        assertThat(bodyCaptor.getAllValues())
            .anyMatch(body -> body.contains(substituteProductId.toString()) && body.contains("2x"))
            .anyMatch(body -> body.contains("INR 125.00") && body.contains("Gateway timeout"))
            .anyMatch(body -> body.contains("SILVER") && body.contains("GOLD") && body.contains("2500"))
            .anyMatch(body -> body.contains("2026-11-21T18:00:00Z") && body.contains("EXCESSIVE_FAILED_ATTEMPTS"));

        assertThat(logCaptor.getAllValues())
            .allSatisfy(entry -> assertThat(entry.getTemplateContext())
                .doesNotContain("asha@example.com", "+919876543210", "\"email\"", "\"phone\""));
    }
}
