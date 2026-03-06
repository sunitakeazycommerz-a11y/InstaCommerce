package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.exception.PaymentDeclinedException;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import com.instacommerce.order.workflow.model.PaymentResult;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestPaymentClient implements PaymentClient {
    // TODO: Migrate from RestTemplate to WebClient for non-blocking I/O in Temporal activities
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestPaymentClient(RestTemplateBuilder builder, OrderProperties orderProperties,
                             @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                             @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(java.time.Duration.ofSeconds(2))
            .setReadTimeout(java.time.Duration.ofSeconds(10))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getPayment().getBaseUrl();
    }

    @Override
    public PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey) {
        PaymentAuthorizeRequest request = new PaymentAuthorizeRequest(orderId, amountCents, currency, idempotencyKey);
        try {
            PaymentAuthorizeResponse response = restTemplate.postForObject(
                baseUrl + "/payments/authorize", request, PaymentAuthorizeResponse.class);
            if (response == null || response.paymentId() == null) {
                throw new PaymentDeclinedException("Payment authorization failed");
            }
            return new PaymentResult(response.paymentId().toString(), response.status());
        } catch (HttpStatusCodeException ex) {
            if (Objects.equals(ex.getStatusCode(), HttpStatus.UNPROCESSABLE_ENTITY)) {
                throw new PaymentDeclinedException("Payment authorization failed");
            }
            throw ex;
        }
    }

    @Override
    public void capturePayment(String paymentId) {
        restTemplate.postForLocation(baseUrl + "/payments/" + paymentId + "/capture", null);
    }

    @Override
    public void voidPayment(String paymentId) {
        restTemplate.postForLocation(baseUrl + "/payments/" + paymentId + "/void", null);
    }
}

