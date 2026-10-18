package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestPricingQuoteClient implements PricingQuoteClient {
    private static final Logger logger = LoggerFactory.getLogger(RestPricingQuoteClient.class);
    private final RestClient restClient;
    private final String baseUrl;

    public RestPricingQuoteClient(RestClient.Builder builder, OrderProperties orderProperties,
                                  @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                  @Value("${internal.service.token}") String serviceToken) {
        this.restClient = builder
            .requestFactory(clientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(10)))
            .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getPricing().getBaseUrl();
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "validateQuoteFallback")
    @Retry(name = "pricingService")
    @Bulkhead(name = "pricingService", fallbackMethod = "validateQuoteFallback")
    public boolean validateQuote(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents) {
        try {
            QuoteValidateRequest request = new QuoteValidateRequest(quoteId, quoteToken, totalCents, subtotalCents, discountCents);
            restClient.post()
                .uri(baseUrl + "/pricing/quotes/validate")
                .body(request)
                .retrieve()
                .onStatus(status -> status.value() == 409,
                    (clientRequest, clientResponse) -> {
                        logger.warn("Quote validation conflict for quoteId={}", quoteId);
                        throw new QuoteValidationConflictException("Quote expired or already used: " + quoteId);
                    })
                .toBodilessEntity();
            return true;
        } catch (QuoteValidationConflictException e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private boolean validateQuoteFallback(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents, Throwable t) {
        logger.warn("Resilience fallback for pricingService validateQuote quoteId={}: {}", quoteId, t.getMessage());
        return false;
    }

    private SimpleClientHttpRequestFactory clientHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    record QuoteValidateRequest(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents) {}

    private static class QuoteValidationConflictException extends RuntimeException {
        QuoteValidationConflictException(String message) {
            super(message);
        }
    }
}
