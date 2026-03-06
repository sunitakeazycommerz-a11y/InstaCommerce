package com.instacommerce.notification.provider;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
@Profile("prod")
public class TwilioSmsProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(TwilioSmsProvider.class);

    private final RestTemplate restTemplate;
    private final NotificationProperties notificationProperties;

    public TwilioSmsProvider(RestTemplateBuilder builder, NotificationProperties notificationProperties) {
        this.restTemplate = builder
            .setConnectTimeout(java.time.Duration.ofSeconds(2))
            .setReadTimeout(java.time.Duration.ofSeconds(5))
            .build();
        this.notificationProperties = notificationProperties;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.SMS;
    }

    @Override
    public String send(NotificationSendRequest request) {
        NotificationProperties.Twilio twilio = notificationProperties.getProviders().getTwilio();
        if (isBlank(twilio.getAccountSid()) || isBlank(twilio.getAuthToken()) || isBlank(twilio.getFromNumber())) {
            throw new ProviderPermanentException("Twilio credentials are not configured");
        }
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilio.getAccountSid() + "/Messages.json";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", twilio.getFromNumber());
        form.add("To", request.recipient());
        form.add("Body", request.body());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.AUTHORIZATION, basicAuth(twilio.getAccountSid(), twilio.getAuthToken()));
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ProviderTemporaryException("Twilio failed with status " + response.getStatusCode());
            }
            return "twilio:" + response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ProviderPermanentException("Twilio rejected request: " + ex.getStatusText());
            }
            logger.warn("Twilio temporary failure: {}", ex.getStatusText());
            throw new ProviderTemporaryException("Twilio temporary failure", ex);
        } catch (Exception ex) {
            throw new ProviderTemporaryException("Twilio request failed", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String basicAuth(String user, String password) {
        String token = user + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}

