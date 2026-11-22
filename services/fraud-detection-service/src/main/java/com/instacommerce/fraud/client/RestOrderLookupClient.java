package com.instacommerce.fraud.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.instacommerce.fraud.config.FraudProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class RestOrderLookupClient implements OrderLookupClient {

    private static final Logger log = LoggerFactory.getLogger(RestOrderLookupClient.class);

    private final RestClient restClient;

    @Autowired
    public RestOrderLookupClient(
            FraudProperties fraudProperties,
            @Value("${internal.service.name:${spring.application.name}}") String serviceName,
            @Value("${internal.service.token:}") String serviceToken) {
        this(createDefaultBuilder(), fraudProperties.getOrder().getBaseUrl(), serviceName, serviceToken);
    }

    RestOrderLookupClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String serviceName,
            String serviceToken) {
        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            request.getHeaders().set("X-Internal-Service", serviceName);
            request.getHeaders().set("X-Internal-Token", serviceToken);
            return execution.execute(request, body);
        };
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestInterceptor(authInterceptor)
                .build();
    }

    private static RestClient.Builder createDefaultBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Override
    public Optional<OrderSnapshot> findOrder(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        try {
            OrderResponse response = restClient.get()
                    .uri("/admin/orders/{orderId}", orderId)
                    .retrieve()
                    .body(OrderResponse.class);
            if (response == null || response.id() == null || response.userId() == null) {
                return Optional.empty();
            }
            return Optional.of(new OrderSnapshot(response.id(), response.userId()));
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("Order {} not found in order-service (404)", orderId);
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderResponse(UUID id, UUID userId) {
    }
}
