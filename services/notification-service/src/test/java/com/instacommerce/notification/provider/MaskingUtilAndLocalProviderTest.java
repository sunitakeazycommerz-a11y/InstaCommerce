package com.instacommerce.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.service.MaskingUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class MaskingUtilAndLocalProviderTest {

    @Test
    @DisplayName("MaskingUtil masks PUSH tokens without stripping letters like maskPhone")
    void maskingUtilHandlesPushEmailAndPhoneDistinctly() {
        String pushToken = "eXampleFcmToken:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx";
        String maskedPush = MaskingUtil.maskRecipient(pushToken, NotificationChannel.PUSH);
        assertThat(maskedPush).isEqualTo("eXam****RYqx");
        assertThat(MaskingUtil.maskRecipient(pushToken)).isEqualTo("eXam****RYqx");

        assertThat(MaskingUtil.maskRecipient("asha@example.com", NotificationChannel.EMAIL))
            .isEqualTo("a***@example.com");
        assertThat(MaskingUtil.maskRecipient("+919876543210", NotificationChannel.SMS))
            .isEqualTo("+91****3210");
        assertThat(MaskingUtil.maskToken("short")).isEqualTo("****");
    }

    @Test
    @DisplayName("Placeholder provider credentials succeed only in non-prod local profile and fail closed in prod")
    void localProfileAllowsPlaceholderNoopWhileProdFailsClosed() {
        NotificationProperties props = new NotificationProperties();
        props.getProviders().getSendgrid().setApiKey("SG.local_placeholder");
        props.getProviders().getSendgrid().setFromEmail("no-reply@localhost.instacommerce.dev");
        props.getProviders().getTwilio().setAccountSid("local_twilio_placeholder");
        props.getProviders().getTwilio().setAuthToken("local_twilio_placeholder");
        props.getProviders().getTwilio().setFromNumber("+15005550006");
        props.getProviders().getFcm().setProjectId("local-dev");
        props.getProviders().getFcm().setAccessToken("local_fcm_placeholder");

        MockEnvironment localEnv = new MockEnvironment().withProperty("spring.profiles.active", "local");
        localEnv.setActiveProfiles("local");

        assertThat(new SendGridEmailProvider(props, localEnv).send(
            new NotificationSendRequest(NotificationChannel.EMAIL, "u@example.com", "Subj", "Body")))
            .isEqualTo("local-noop:sendgrid");
        assertThat(new TwilioSmsProvider(props, localEnv).send(
            new NotificationSendRequest(NotificationChannel.SMS, "+919876543210", null, "Body")))
            .isEqualTo("local-noop:twilio");
        assertThat(new FcmPushNotificationProvider(RestClient.builder(), props, localEnv).send(
            new NotificationSendRequest(NotificationChannel.PUSH, "fcm-token-123456", "Title", "Body")))
            .isEqualTo("local-noop:fcm");

        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new SendGridEmailProvider(props, prodEnv).send(
            new NotificationSendRequest(NotificationChannel.EMAIL, "u@example.com", "Subj", "Body")))
            .isInstanceOf(ProviderPermanentException.class);
        assertThatThrownBy(() -> new TwilioSmsProvider(props, prodEnv).send(
            new NotificationSendRequest(NotificationChannel.SMS, "+919876543210", null, "Body")))
            .isInstanceOf(ProviderPermanentException.class);
        assertThatThrownBy(() -> new FcmPushNotificationProvider(RestClient.builder(), props, prodEnv).send(
            new NotificationSendRequest(NotificationChannel.PUSH, "fcm-token-123456", "Title", "Body")))
            .isInstanceOf(ProviderPermanentException.class);
    }
}
