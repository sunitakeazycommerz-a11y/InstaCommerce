package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class RestCartClient implements CartClient {
    // TODO: Migrate from RestTemplate to WebClient for non-blocking I/O in Temporal activities
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestCartClient(RestTemplateBuilder builder, OrderProperties orderProperties,
                          @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                          @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(java.time.Duration.ofSeconds(2))
            .setReadTimeout(java.time.Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getCart().getBaseUrl();
    }

    @Override
    public void clearCart(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        restTemplate.exchange(baseUrl + "/cart", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }
}
