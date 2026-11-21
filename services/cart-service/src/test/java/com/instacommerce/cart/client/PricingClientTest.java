package com.instacommerce.cart.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.cart.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class PricingClientTest {

    private static final String BASE_URL = "http://pricing-service:8087";
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void getPriceMaps404ToProductUnavailableInsteadOf503() {
        UUID productId = UUID.randomUUID();
        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(productId)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> pricingClient.getPrice(productId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("PRODUCT_UNAVAILABLE");
                });
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

    @Test
    void getPriceRethrowsResourceAccessExceptionForRetry() {
        UUID productId = UUID.randomUUID();

        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(productId)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> pricingClient.getPrice(productId))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("Read timed out");
    }

    @Test
    void getPricesBatchParsesBatchWrapperWithUnavailableProducts() throws Exception {
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        JsonNode payload = objectMapper.readTree("""
                {
                  "items": [
                    {"productId": "%s", "unitPriceCents": 2500, "quantity": 1, "lineTotalCents": 2500, "currency": "INR"}
                  ],
                  "unavailableProductIds": ["%s"]
                }
                """.formatted(activeId, inactiveId));

        when(restTemplate.postForObject(
                eq(BASE_URL + "/pricing/products/batch"),
                any(),
                eq(JsonNode.class)))
                .thenReturn(payload);

        Map<UUID, PricingClient.PriceResponse> result = pricingClient.getPricesBatch(List.of(activeId, inactiveId));

        assertThat(result).containsOnlyKeys(activeId);
        assertThat(result.get(activeId).unitPriceCents()).isEqualTo(2500L);
    }

    @Test
    void getPricesBatchFallsBackGracefullyOn404WithoutThrowing503() {
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();

        when(restTemplate.postForObject(
                eq(BASE_URL + "/pricing/products/batch"),
                any(),
                eq(JsonNode.class)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(activeId)))
                .thenReturn(new PricingClient.PriceResponse(activeId, 1500L, "INR"));
        when(restTemplate.getForObject(
                eq(BASE_URL + "/pricing/products/{productId}"),
                eq(PricingClient.PriceResponse.class),
                eq(inactiveId)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        Map<UUID, PricingClient.PriceResponse> result = pricingClient.getPricesBatch(List.of(activeId, inactiveId));

        assertThat(result).containsOnlyKeys(activeId);
        assertThat(result.get(activeId).unitPriceCents()).isEqualTo(1500L);
    }
}
