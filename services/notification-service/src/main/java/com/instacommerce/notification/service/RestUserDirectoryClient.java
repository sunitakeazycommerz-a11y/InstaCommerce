package com.instacommerce.notification.service;

import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestUserDirectoryClient implements UserDirectoryClient {
    private static final Logger logger = LoggerFactory.getLogger(RestUserDirectoryClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestUserDirectoryClient(RestTemplateBuilder builder, NotificationProperties notificationProperties,
                                   @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                   @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = notificationProperties.getIdentity().getBaseUrl();
    }

    @Override
    public Optional<UserContact> findUser(UUID userId) {
        try {
            IdentityUserResponse response = restTemplate.getForObject(
                baseUrl + "/admin/users/" + userId, IdentityUserResponse.class);
            if (response == null || response.id() == null) {
                return Optional.empty();
            }
            String name = buildName(response.firstName(), response.lastName());
            return Optional.of(new UserContact(response.id(), response.email(), response.phone(), name, null));
        } catch (ResourceAccessException ex) {
            logger.error("Identity-service unreachable while fetching user {}: {}", userId, ex.getMessage());
            return Optional.empty();
        } catch (RestClientException ex) {
            logger.warn("Failed to fetch user contact for {}", userId, ex);
            return Optional.empty();
        }
    }

    private static String buildName(String firstName, String lastName) {
        if (firstName == null && lastName == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (firstName != null) {
            sb.append(firstName);
        }
        if (lastName != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(lastName);
        }
        return sb.toString();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityUserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone
    ) {
    }
}
