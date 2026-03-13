package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestPaymentClient implements PaymentClient {
    private static final Logger logger = LoggerFactory.getLogger(RestPaymentClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestPaymentClient(FulfillmentProperties fulfillmentProperties,
                             @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                             @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getPayment().getBaseUrl();
    }

    @Override
    public void refund(UUID paymentId, long amountCents, String reason, String idempotencyKey) {
        RefundRequest request = new RefundRequest(amountCents, reason, idempotencyKey);
        try {
            restTemplate.postForObject(baseUrl + "/payments/" + paymentId + "/refund", request, Object.class);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            logger.error("Refund HTTP error for payment {} amount {}: {} {}",
                paymentId, amountCents, ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            logger.error("Refund call failed for payment {} amount {}: {}",
                paymentId, amountCents, ex.getMessage());
        }
    }
}
