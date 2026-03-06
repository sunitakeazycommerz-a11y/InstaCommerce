package com.instacommerce.notification.provider;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.restclient.RestTemplateBuilder;

@Component
@Profile("prod")
public class SendGridEmailProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(SendGridEmailProvider.class);
    private static final String SENDGRID_URL = "https://api.sendgrid.com/v3/mail/send";

    private final RestTemplate restTemplate;
    private final NotificationProperties notificationProperties;

    public SendGridEmailProvider(RestTemplateBuilder builder, NotificationProperties notificationProperties) {
        this.restTemplate = builder
            .setConnectTimeout(java.time.Duration.ofSeconds(2))
            .setReadTimeout(java.time.Duration.ofSeconds(5))
            .build();
        this.notificationProperties = notificationProperties;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL;
    }

    @Override
    public String send(NotificationSendRequest request) {
        String apiKey = notificationProperties.getProviders().getSendgrid().getApiKey();
        String fromEmail = notificationProperties.getProviders().getSendgrid().getFromEmail();
        String listUnsubscribeUrl = notificationProperties.getProviders().getSendgrid().getListUnsubscribeUrl();
        if (apiKey == null || apiKey.isBlank() || fromEmail == null || fromEmail.isBlank()) {
            throw new ProviderPermanentException("SendGrid credentials are not configured");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("personalizations", new Object[] {
            Map.of("to", new Object[] { Map.of("email", request.recipient()) },
                "subject", request.subject())
        });
        payload.put("from", Map.of("email", fromEmail));
        payload.put("content", new Object[] { Map.of("type", "text/html", "value", request.body()) });
        if (listUnsubscribeUrl != null && !listUnsubscribeUrl.isBlank()) {
            payload.put("headers", Map.of(
                "List-Unsubscribe", "<" + listUnsubscribeUrl.trim() + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"
            ));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                SENDGRID_URL, new HttpEntity<>(payload, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ProviderTemporaryException("SendGrid failed with status " + response.getStatusCode());
            }
            return "sendgrid:" + response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ProviderPermanentException("SendGrid rejected request: " + ex.getStatusText());
            }
            logger.warn("SendGrid temporary failure: {}", ex.getStatusText());
            throw new ProviderTemporaryException("SendGrid temporary failure", ex);
        } catch (Exception ex) {
            throw new ProviderTemporaryException("SendGrid request failed", ex);
        }
    }
}

