package com.instacommerce.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class NotificationProviderProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(NotificationProperties.class, NotificationProperties::new)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withUserConfiguration(
            FcmPushNotificationProvider.class,
            SendGridEmailProvider.class,
            TwilioSmsProvider.class,
            LoggingProvider.class,
            NotificationProviderRegistry.class);

    @Test
    @DisplayName("gcp profile wires real push, email, and sms providers instead of LoggingProvider")
    void gcpProfileWiresRealChannelProviders() {
        contextRunner
            .withPropertyValues("spring.profiles.active=gcp")
            .run(context -> {
                assertThat(context).hasSingleBean(FcmPushNotificationProvider.class);
                assertThat(context).hasSingleBean(SendGridEmailProvider.class);
                assertThat(context).hasSingleBean(TwilioSmsProvider.class);
                assertThat(context).doesNotHaveBean(LoggingProvider.class);

                NotificationProviderRegistry registry = context.getBean(NotificationProviderRegistry.class);
                assertThat(registry.findProvider(NotificationChannel.PUSH))
                    .containsInstanceOf(FcmPushNotificationProvider.class);
                assertThat(registry.findProvider(NotificationChannel.EMAIL))
                    .containsInstanceOf(SendGridEmailProvider.class);
                assertThat(registry.findProvider(NotificationChannel.SMS))
                    .containsInstanceOf(TwilioSmsProvider.class);
            });
    }

    @Test
    @DisplayName("prod profile wires real push, email, and sms providers instead of LoggingProvider")
    void prodProfileWiresRealChannelProviders() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                assertThat(context).hasSingleBean(FcmPushNotificationProvider.class);
                assertThat(context).hasSingleBean(SendGridEmailProvider.class);
                assertThat(context).hasSingleBean(TwilioSmsProvider.class);
                assertThat(context).doesNotHaveBean(LoggingProvider.class);
            });
    }

    @Test
    @DisplayName("non-prod/non-gcp profile wires LoggingProvider fallback")
    void devProfileWiresLoggingProvider() {
        contextRunner
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> {
                assertThat(context).hasSingleBean(LoggingProvider.class);
                assertThat(context).doesNotHaveBean(FcmPushNotificationProvider.class);
                assertThat(context).doesNotHaveBean(SendGridEmailProvider.class);
                assertThat(context).doesNotHaveBean(TwilioSmsProvider.class);
            });
    }
}
