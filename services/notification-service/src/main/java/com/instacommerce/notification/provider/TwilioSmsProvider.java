package com.instacommerce.notification.provider;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.service.MaskingUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
@Profile({"prod", "gcp"})
public class TwilioSmsProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(TwilioSmsProvider.class);
    private static final String LOCAL_PLACEHOLDER_PREFIX = "local_twilio_placeholder";

    private final RestTemplate restTemplate;
    private final NotificationProperties notificationProperties;
    private final Environment environment;

    public TwilioSmsProvider(NotificationProperties notificationProperties) {
        this(notificationProperties, null);
    }

    @Autowired
    public TwilioSmsProvider(NotificationProperties notificationProperties, Environment environment) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(requestFactory);
        this.notificationProperties = notificationProperties;
        this.environment = environment;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.SMS;
    }

    @Override
    @CircuitBreaker(name = "smsProvider", fallbackMethod = "smsFallback")
    public String send(NotificationSendRequest request) {
        NotificationProperties.Twilio twilio = notificationProperties.getProviders().getTwilio();
        boolean placeholder = isPlaceholder(twilio.getAccountSid()) || isPlaceholder(twilio.getAuthToken());
        if (placeholder) {
            if (isLocalNonProdProfile()) {
                logger.info("Local noop SMS dispatch to {}",
                    MaskingUtil.maskRecipient(request.recipient(), NotificationChannel.SMS));
                return "local-noop:twilio";
            }
            throw new ProviderPermanentException("Twilio local placeholder credentials cannot be used outside local profile");
        }
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

    private boolean isLocalNonProdProfile() {
        return environment != null
            && environment.acceptsProfiles(Profiles.of("local"))
            && !environment.acceptsProfiles(Profiles.of("prod", "gcp"));
    }

    private boolean isPlaceholder(String value) {
        return value != null && value.trim().startsWith(LOCAL_PLACEHOLDER_PREFIX);
    }

    @SuppressWarnings("unused")
    private String smsFallback(NotificationSendRequest request, Exception ex) {
        logger.warn("notification.circuit_breaker_open provider=sms recipient={}",
            MaskingUtil.maskRecipient(request.recipient(), NotificationChannel.SMS), ex);
        throw new ProviderTemporaryException("SMS provider circuit breaker open", ex);
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
