package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.instacommerce.notification.infrastructure.retry.NotificationProviderSender;
import com.instacommerce.notification.infrastructure.retry.NotificationRetryJob;
import com.instacommerce.notification.infrastructure.retry.RetryableNotificationSender;
import com.instacommerce.notification.provider.NotificationProvider;
import com.instacommerce.notification.provider.NotificationProviderRegistry;
import com.instacommerce.notification.provider.NotificationSendRequest;
import com.instacommerce.notification.provider.ProviderPermanentException;
import com.instacommerce.notification.provider.ProviderTemporaryException;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import com.instacommerce.notification.repository.NotificationLogRepository;
import com.instacommerce.notification.template.TemplateRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
class NotificationFallbackQuietHoursAndDedupTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DeduplicationService deduplicationService;

    @Mock
    private NotificationLogRepository logRepository;

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

    private TemplateRegistry templateRegistry;
    private TemplateService templateService;

    @BeforeEach
    void setUp() {
        templateRegistry = new TemplateRegistry();
        templateService = new TemplateService(new DefaultResourceLoader());
    }

    @Test
    @DisplayName("PUSH -> SMS -> EMAIL fallback cascades on provider failure until EMAIL succeeds without hitting DLQ")
    void pushToSmsToEmailFallbackOnProviderFailure() {
        UUID userId = UUID.randomUUID();
        String eventId = "status:ord-101:ETABreachRisk";

        NotificationProvider pushProvider = mock(NotificationProvider.class);
        when(pushProvider.supports(NotificationChannel.PUSH)).thenReturn(true);
        when(pushProvider.send(any())).thenThrow(new ProviderPermanentException("FCM token unregistered"));

        NotificationProvider smsProvider = mock(NotificationProvider.class);
        when(smsProvider.supports(NotificationChannel.SMS)).thenReturn(true);
        when(smsProvider.send(any())).thenThrow(new ProviderPermanentException("Carrier rejected SMS"));

        NotificationProvider emailProvider = mock(NotificationProvider.class);
        when(emailProvider.supports(NotificationChannel.EMAIL)).thenReturn(true);
        when(emailProvider.send(any())).thenReturn("sg-msg-999");

        NotificationProviderRegistry registry = new NotificationProviderRegistry(
            List.of(pushProvider, smsProvider, emailProvider));

        NotificationProviderSender providerSender = new NotificationProviderSender(registry, logRepository, metrics);
        ChannelFallbackService fallbackService = new ChannelFallbackService(
            deduplicationService,
            templateRegistry,
            templateService,
            userPreferenceService,
            userDirectoryClient,
            providerSender
        );
        RetryableNotificationSender sender = new RetryableNotificationSender(
            providerSender, dlqPublisher, fallbackService, userDirectoryClient, deviceTokenRepository, objectMapper);

        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false);
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.SMS, false)).thenReturn(true);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.EMAIL, false)).thenReturn(true);
        when(userDirectoryClient.findUser(userId))
            .thenReturn(Optional.of(new UserContact(userId, "user@example.com", "+919876543210", "Asha", "en")));

        NotificationLog pushLog = new NotificationLog();
        pushLog.setChannel(NotificationChannel.PUSH);
        NotificationLog smsLog = new NotificationLog();
        smsLog.setChannel(NotificationChannel.SMS);
        NotificationLog emailLog = new NotificationLog();
        emailLog.setChannel(NotificationChannel.EMAIL);

        when(deduplicationService.createLog(any(NotificationRequest.class), anyString()))
            .thenReturn(smsLog, emailLog);

        NotificationRequest pushReq = new NotificationRequest(
            eventId,
            "ETABreachRisk",
            userId,
            NotificationChannel.PUSH,
            "eta_delayed",
            "fcm-token-1",
            Map.of("orderId", "ord-101", "eta", "25 mins", "userName", "Asha")
        );

        sender.send(pushReq, pushLog, null, "Order #ord-101 delayed");

        assertThat(pushLog.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(smsLog.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(emailLog.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(emailLog.getProviderRef()).isEqualTo("sg-msg-999");
        verify(dlqPublisher, never()).publish(any(), anyString());
    }

    @Test
    @DisplayName("Promotional notifications are suppressed during user quiet hours while transactional notifications send")
    void promotionalNotificationsSuppressedDuringQuietHours() {
        UUID userId = UUID.randomUUID();
        Clock quietNight = Clock.fixed(Instant.parse("2026-11-19T23:15:00Z"), ZoneId.of("UTC"));
        NotificationProperties props = new NotificationProperties();
        UserPreferenceService realPrefService = new UserPreferenceService(props, "notification-service", "tok", quietNight);
        UserPreferenceService.Preferences quietPrefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false, true, LocalTime.of(22, 0), LocalTime.of(7, 0), "UTC");

        when(userPreferenceService.getPreferences(userId)).thenReturn(quietPrefs);
        when(userPreferenceService.allowNotification(eq(quietPrefs), any(), eq(true)))
            .thenAnswer(inv -> realPrefService.allowNotification(quietPrefs, inv.getArgument(1), true));
        when(userPreferenceService.isWithinQuietHours(quietPrefs)).thenReturn(true);
        when(userDirectoryClient.findUser(userId))
            .thenReturn(Optional.of(new UserContact(userId, "promo@example.com", "+919876543210", "Rohan", "en")));
        when(deduplicationService.createLog(any(NotificationRequest.class), eq("n/a")))
            .thenAnswer(inv -> new NotificationLog());

        RetryableNotificationSender mockSender = mock(RetryableNotificationSender.class);
        ChannelFallbackService mockFallback = mock(ChannelFallbackService.class);
        NotificationService service = new NotificationService(
            templateRegistry, templateService, deduplicationService, logRepository,
            mockSender, dlqPublisher, userPreferenceService, userDirectoryClient,
            orderLookupClient, metrics, props, deviceTokenRepository, mockFallback);

        EventEnvelope promoEnvelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "PromotionalCampaign",
            "Campaign",
            "camp-1",
            Instant.now(),
            "1",
            "marketing-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString()))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("marketing.events", 0, 1L, "k1", "{}");

        service.handleEvent(record, promoEnvelope);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository, times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
            .allSatisfy(entry -> {
                assertThat(entry.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
                assertThat(entry.getLastError()).isEqualTo("Suppressed during quiet hours");
            });
        verify(mockSender, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Repeated order status notifications with distinct envelope IDs deduplicate on canonical orderId + eventType key")
    void repeatedOrderStatusEventsDeduplicateAcrossDistinctEnvelopeIds() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false);
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.SMS, false)).thenReturn(true);
        when(userDirectoryClient.findUser(userId))
            .thenReturn(Optional.of(new UserContact(userId, "asha@example.com", "+919876543210", "Asha", "en")));

        NotificationLog firstLog = new NotificationLog();
        when(deduplicationService.createLog(any(NotificationRequest.class), anyString()))
            .thenReturn(firstLog)
            .thenReturn(null);

        RetryableNotificationSender mockSender = mock(RetryableNotificationSender.class);
        ChannelFallbackService mockFallback = mock(ChannelFallbackService.class);
        NotificationService service = new NotificationService(
            templateRegistry, templateService, deduplicationService, logRepository,
            mockSender, dlqPublisher, userPreferenceService, userDirectoryClient,
            orderLookupClient, metrics, new NotificationProperties(), deviceTokenRepository, mockFallback);

        EventEnvelope firstEnvelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPacked",
            "Order",
            orderId.toString(),
            Instant.now(),
            "1",
            "fulfillment-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString(), "orderId", orderId.toString()))
        );
        EventEnvelope duplicateStatusEnvelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPacked",
            "Order",
            orderId.toString(),
            Instant.now(),
            "1",
            "fulfillment-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", userId.toString(), "orderId", orderId.toString()))
        );

        ConsumerRecord<String, String> rec1 = new ConsumerRecord<>("fulfillment.events", 0, 1L, "k1", "{}");
        ConsumerRecord<String, String> rec2 = new ConsumerRecord<>("fulfillment.events", 0, 2L, "k2", "{}");

        service.handleEvent(rec1, firstEnvelope);
        service.handleEvent(rec2, duplicateStatusEnvelope);

        ArgumentCaptor<NotificationRequest> reqCaptor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(deduplicationService, times(2)).createLog(reqCaptor.capture(), anyString());
        List<NotificationRequest> requests = reqCaptor.getAllValues();
        assertThat(requests.get(0).eventId()).isEqualTo("status:" + orderId + ":OrderPacked");
        assertThat(requests.get(1).eventId()).isEqualTo(requests.get(0).eventId());
        verify(mockSender, times(1)).send(any(), eq(firstLog), any(), anyString());
    }

    @Test
    @DisplayName("Retried fallback restores persisted template_context and renders SMS and Email without MustacheException")
    void retriedFallbackRestoresTemplateContextWithoutMustacheException() {
        UUID userId = UUID.randomUUID();
        String eventId = "status:ord-retry-99:ETABreachRisk";

        NotificationProvider pushProvider = mock(NotificationProvider.class);
        when(pushProvider.supports(NotificationChannel.PUSH)).thenReturn(true);
        when(pushProvider.send(any())).thenThrow(new ProviderTemporaryException("FCM 503"));

        NotificationProvider smsProvider = mock(NotificationProvider.class);
        when(smsProvider.supports(NotificationChannel.SMS)).thenReturn(true);
        when(smsProvider.send(any())).thenThrow(new ProviderPermanentException("Carrier SMS rejected"));

        NotificationProvider emailProvider = mock(NotificationProvider.class);
        when(emailProvider.supports(NotificationChannel.EMAIL)).thenReturn(true);
        when(emailProvider.send(any())).thenReturn("sg-email-ok");

        NotificationProviderRegistry registry = new NotificationProviderRegistry(
            List.of(pushProvider, smsProvider, emailProvider));
        NotificationProviderSender providerSender = new NotificationProviderSender(registry, logRepository, metrics);
        DeduplicationService realDedup = new DeduplicationService(logRepository, objectMapper);
        when(logRepository.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelFallbackService fallbackService = new ChannelFallbackService(
            realDedup, templateRegistry, templateService, userPreferenceService, userDirectoryClient, providerSender);
        RetryableNotificationSender retrySender = new RetryableNotificationSender(
            providerSender, dlqPublisher, fallbackService, userDirectoryClient, deviceTokenRepository, objectMapper);

        NotificationRequest pushReq = new NotificationRequest(
            eventId,
            "ETABreachRisk",
            userId,
            NotificationChannel.PUSH,
            "eta_delayed",
            "fcm-token-retry",
            Map.of(
                "orderId", "ord-retry-99",
                "eta", "18 mins",
                "totalFormatted", "₹320.00",
                "userName", "Asha",
                "phone", "+919876543210",
                "email", "asha@example.com"
            )
        );
        NotificationLog persistedPushLog = realDedup.createLog(pushReq, "fcm-***");
        assertThat(persistedPushLog).isNotNull();
        assertThat(persistedPushLog.getTemplateContext())
            .contains("ord-retry-99", "18 mins", "₹320.00", "Asha")
            .doesNotContain("+919876543210", "asha@example.com", "\"phone\"", "\"email\"");

        persistedPushLog.setRetryCount(2);
        persistedPushLog.setNextRetryAt(Instant.now().minusSeconds(5));
        persistedPushLog.setStatus(NotificationStatus.RETRY_PENDING);
        persistedPushLog.setSubject("Delivery delayed");
        persistedPushLog.setBody("Delayed");

        DeviceToken activeToken = new DeviceToken();
        activeToken.setToken("fcm-live-token-99");
        when(deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId))
            .thenReturn(Optional.of(activeToken));

        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false);
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.SMS, false)).thenReturn(true);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.EMAIL, false)).thenReturn(true);
        when(userDirectoryClient.findUser(userId))
            .thenReturn(Optional.of(new UserContact(userId, "asha@example.com", "+919876543210", "Asha", "en")));
        when(logRepository.findPendingForRetry(any(Instant.class), anyInt())).thenReturn(List.of(persistedPushLog));

        NotificationRetryJob retryJob = new NotificationRetryJob(logRepository, retrySender, new NotificationProperties());
        retryJob.retryPendingNotifications();

        ArgumentCaptor<NotificationSendRequest> pushCaptor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(pushProvider).send(pushCaptor.capture());
        assertThat(pushCaptor.getValue().recipient()).isEqualTo("fcm-live-token-99");

        ArgumentCaptor<NotificationSendRequest> smsCaptor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(smsProvider).send(smsCaptor.capture());
        assertThat(smsCaptor.getValue().recipient()).isEqualTo("+919876543210");
        assertThat(smsCaptor.getValue().body())
            .contains("Asha")
            .contains("ord-retry-99")
            .contains("18 mins");

        ArgumentCaptor<NotificationSendRequest> emailCaptor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(emailProvider).send(emailCaptor.capture());
        assertThat(emailCaptor.getValue().recipient()).isEqualTo("asha@example.com");
        assertThat(emailCaptor.getValue().body())
            .contains("ord-retry-99")
            .contains("18 mins");
        verify(dlqPublisher, never()).publish(any(), anyString());
    }

    @Test
    @DisplayName("Scheduled SMS retry dispatches to live unmasked phone and triggers fallback if contact disappears")
    void scheduledRetryResolvesLiveContactOrFallsBackWhenMissing() {
        UUID userId = UUID.randomUUID();
        NotificationProvider smsProvider = mock(NotificationProvider.class);
        when(smsProvider.supports(NotificationChannel.SMS)).thenReturn(true);
        when(smsProvider.send(any())).thenReturn("twilio-sid-live");

        NotificationProvider emailProvider = mock(NotificationProvider.class);
        when(emailProvider.supports(NotificationChannel.EMAIL)).thenReturn(true);
        when(emailProvider.send(any())).thenReturn("sg-live-1");

        NotificationProviderRegistry registry = new NotificationProviderRegistry(
            List.of(smsProvider, emailProvider));
        NotificationProviderSender providerSender = new NotificationProviderSender(registry, logRepository, metrics);
        DeduplicationService realDedup = new DeduplicationService(logRepository, objectMapper);
        when(logRepository.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelFallbackService fallbackService = new ChannelFallbackService(
            realDedup, templateRegistry, templateService, userPreferenceService, userDirectoryClient, providerSender);
        RetryableNotificationSender retrySender = new RetryableNotificationSender(
            providerSender, dlqPublisher, fallbackService, userDirectoryClient, deviceTokenRepository, objectMapper);

        NotificationLog smsRetryLog = new NotificationLog();
        smsRetryLog.setUserId(userId);
        smsRetryLog.setEventId("status:ord-live-1:OrderPlaced");
        smsRetryLog.setEventType("OrderPlaced");
        smsRetryLog.setChannel(NotificationChannel.SMS);
        smsRetryLog.setTemplateId("order_confirmed");
        smsRetryLog.setRecipient("+91****3210");
        smsRetryLog.setTemplateContext("{\"orderId\":\"ord-live-1\",\"totalFormatted\":\"₹250.00\",\"userName\":\"Asha\"}");
        smsRetryLog.setRetryCount(1);
        smsRetryLog.setBody("Order confirmed");

        when(userDirectoryClient.findUser(userId))
            .thenReturn(Optional.of(new UserContact(userId, "asha@example.com", "+919876543210", "Asha", "en")));

        retrySender.retry(smsRetryLog);

        ArgumentCaptor<NotificationSendRequest> smsCaptor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(smsProvider).send(smsCaptor.capture());
        assertThat(smsCaptor.getValue().recipient()).isEqualTo("+919876543210");
        assertThat(smsRetryLog.getStatus()).isEqualTo(NotificationStatus.SENT);

        NotificationLog pushFallbackLog = new NotificationLog();
        pushFallbackLog.setUserId(userId);
        pushFallbackLog.setEventId("status:ord-live-2:OrderPlaced");
        pushFallbackLog.setEventType("OrderPlaced");
        pushFallbackLog.setChannel(NotificationChannel.PUSH);
        pushFallbackLog.setTemplateId("order_confirmed");
        pushFallbackLog.setRecipient("fcm-***");
        pushFallbackLog.setTemplateContext(
            "{\"orderId\":\"ord-live-2\",\"totalFormatted\":\"₹250.00\",\"eta\":\"15 mins\",\"userName\":\"Asha\"}");

        when(deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId))
            .thenReturn(Optional.empty());
        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            userId, false, false, false, false);
        when(userPreferenceService.getPreferences(userId)).thenReturn(prefs);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.SMS, false)).thenReturn(false);
        when(userPreferenceService.allowNotification(prefs, NotificationChannel.EMAIL, false)).thenReturn(true);

        retrySender.retry(pushFallbackLog);

        ArgumentCaptor<NotificationSendRequest> emailCaptor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(emailProvider).send(emailCaptor.capture());
        assertThat(emailCaptor.getValue().recipient()).isEqualTo("asha@example.com");
    }
}
