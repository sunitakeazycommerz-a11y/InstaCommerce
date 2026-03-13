package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestCartClient implements CartClient {
    private final RestClient restClient;
    private final String baseUrl;

    public RestCartClient(RestClient.Builder builder, OrderProperties orderProperties,
                           @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                           @Value("${internal.service.token}") String serviceToken) {
        this.restClient = builder
            .requestFactory(clientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
            .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getCart().getBaseUrl();
    }

    @Override
    public void clearCart(String userId) {
        restClient.method(HttpMethod.DELETE)
            .uri(baseUrl + "/cart")
            .headers(headers -> headers.set("X-User-Id", userId))
            .retrieve()
            .toBodilessEntity();
    }

    private SimpleClientHttpRequestFactory clientHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
