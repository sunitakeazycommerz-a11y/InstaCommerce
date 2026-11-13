package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
            deviceTokenRepository
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
}
