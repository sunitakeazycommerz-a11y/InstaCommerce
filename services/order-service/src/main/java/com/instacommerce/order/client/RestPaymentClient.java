package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.exception.PaymentDeclinedException;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import com.instacommerce.order.workflow.model.PaymentResult;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestPaymentClient implements PaymentClient {
    private final RestClient restClient;
    private final String baseUrl;

    public RestPaymentClient(RestClient.Builder builder, OrderProperties orderProperties,
                             @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                             @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restClient = builder
            .requestFactory(clientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(10)))
            .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getPayment().getBaseUrl();
    }

    @Override
    public PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey) {
        PaymentAuthorizeRequest request = new PaymentAuthorizeRequest(orderId, amountCents, currency, idempotencyKey);
        PaymentAuthorizeResponse response = restClient.post()
            .uri(baseUrl + "/payments/authorize")
            .body(request)
            .retrieve()
            .onStatus(status -> status.value() == 422,
                (clientRequest, clientResponse) -> {
                    throw new PaymentDeclinedException("Payment authorization failed");
                })
            .body(PaymentAuthorizeResponse.class);
        if (response == null || response.paymentId() == null) {
            throw new PaymentDeclinedException("Payment authorization failed");
        }
        return new PaymentResult(response.paymentId().toString(), response.status());
    }

    @Override
    public void capturePayment(String paymentId) {
        restClient.post()
            .uri(baseUrl + "/payments/" + paymentId + "/capture")
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void voidPayment(String paymentId) {
        restClient.post()
            .uri(baseUrl + "/payments/" + paymentId + "/void")
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
