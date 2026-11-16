package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.consumer.IdentityEventConsumer;
import com.instacommerce.notification.consumer.NotificationEventConsumer;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.provider.FcmPushNotificationProvider;
import com.instacommerce.notification.provider.NotificationProviderRegistry;
import com.instacommerce.notification.provider.SendGridEmailProvider;
import com.instacommerce.notification.provider.TwilioSmsProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotificationRemediationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Notification and Identity Kafka consumers rethrow exceptions so DefaultErrorHandler retries and DLT works")
    void kafkaConsumersRethrowExceptionsForDlt() throws Exception {
        NotificationService notificationService = Mockito.mock(NotificationService.class);
        NotificationEventConsumer consumer = new NotificationEventConsumer(notificationService, objectMapper);

        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(), "ETABreachRisk", "Delivery", UUID.randomUUID().toString(),
            Instant.now(), "1", "routing-eta-service", UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", UUID.randomUUID().toString())));
        ConsumerRecord<String, String> deliveryRecord = new ConsumerRecord<>(
            "delivery.events", 0, 1L, "del-1", objectMapper.writeValueAsString(envelope));

        consumer.onFulfillmentEvent(deliveryRecord);
        verify(notificationService).handleEvent(deliveryRecord, envelope);

        ConsumerRecord<String, String> poison = new ConsumerRecord<>("orders.events", 0, 2L, "k", "{bad-json");
        assertThatThrownBy(() -> consumer.onOrderEvent(poison))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> consumer.onPaymentEvent(poison))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> consumer.onRiderEvent(poison))
            .isInstanceOf(RuntimeException.class);

        UserErasureService erasureService = Mockito.mock(UserErasureService.class);
        doThrow(new IllegalStateException("db down")).when(erasureService).anonymizeUser(any(), any());
        IdentityEventConsumer identityConsumer = new IdentityEventConsumer(objectMapper, erasureService);
        EventEnvelope userErased = new EventEnvelope(
            UUID.randomUUID().toString(), "UserErased", "User", UUID.randomUUID().toString(),
            Instant.now(), "1", "identity-service", UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", UUID.randomUUID().toString())));
        ConsumerRecord<String, String> idRecord = new ConsumerRecord<>(
            "identity.events", 0, 1L, "u1", objectMapper.writeValueAsString(userErased));
        assertThatThrownBy(() -> identityConsumer.onIdentityEvent(idRecord))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("UserPreferenceService enforces quiet hours for promotional notifications")
    void quietHoursSuppressesPromotionalNotifications() {
        NotificationProperties props = new NotificationProperties();
        props.getIdentity().setBaseUrl("http://identity-service:8081");
        Clock nightUtc = Clock.fixed(Instant.parse("2026-11-16T23:30:00Z"), ZoneId.of("UTC"));
        UserPreferenceService service = new UserPreferenceService(props, "notification-service", "tok", nightUtc);

        UserPreferenceService.Preferences prefs = new UserPreferenceService.Preferences(
            UUID.randomUUID(), false, false, false, false,
            true, LocalTime.of(22, 0), LocalTime.of(7, 0), "UTC");

        assertThat(service.allowNotification(prefs, NotificationChannel.PUSH, true)).isFalse();
        assertThat(service.allowNotification(prefs, NotificationChannel.PUSH, false)).isTrue();
    }

    @Test
    @DisplayName("Prod NotificationProviderRegistry resolves EMAIL, SMS, and FCM PUSH providers")
    void prodProvidersCoverAllChannelsIncludingPush() {
        NotificationProperties props = new NotificationProperties();
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
            new SendGridEmailProvider(props),
            new TwilioSmsProvider(props),
            new FcmPushNotificationProvider(props)
        ));

        assertThat(registry.findProvider(NotificationChannel.EMAIL)).isPresent();
        assertThat(registry.findProvider(NotificationChannel.SMS)).isPresent();
        assertThat(registry.findProvider(NotificationChannel.PUSH))
            .isPresent()
            .get()
            .isInstanceOf(FcmPushNotificationProvider.class);
    }
}
