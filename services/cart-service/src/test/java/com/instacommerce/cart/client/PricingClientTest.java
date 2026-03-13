package com.instacommerce.cart.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.instacommerce.cart.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class PricingClientTest {

    private static final String BASE_URL = "http://pricing-service:8087";

    @Mock
    private RestTemplate restTemplate;

    private PricingClient pricingClient;

    @BeforeEach
    void setUp() {
        pricingClient = new PricingClient(BASE_URL, "cart-service", "test-token");
        ReflectionTestUtils.setField(pricingClient, "restTemplate", restTemplate);
    }

    @Test
    void getPriceCallsCorrectUrlAndReturnsResponse() {
        UUID productId = UUID.randomUUID();
        PricingClient.PriceResponse expected = new PricingClient.PriceResponse(productId, 1999L, "INR");

        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(productId)))
                .thenReturn(expected);

        PricingClient.PriceResponse result = pricingClient.getPrice(productId);

        assertThat(result).isEqualTo(expected);
        assertThat(result.productId()).isEqualTo(productId);
        assertThat(result.unitPriceCents()).isEqualTo(1999L);
        assertThat(result.currency()).isEqualTo("INR");
    }

    @Test
    void getPriceWrapsRestClientExceptionIntoApiException() {
        UUID productId = UUID.randomUUID();

        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(productId)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> pricingClient.getPrice(productId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("PRICING_UNAVAILABLE");
                });
    }
}
