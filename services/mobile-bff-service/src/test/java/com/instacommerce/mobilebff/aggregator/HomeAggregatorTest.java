package com.instacommerce.mobilebff.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("HomeAggregator Unit Tests")
class HomeAggregatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MobileBffProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MobileBffProperties();
        properties.getUpstream().setTimeoutMs(500);
    }

    private WebClient createMockWebClient(Object responseBody, HttpStatus status) {
        return WebClient.builder()
            .exchangeFunction(clientRequest -> {
                try {
                    String json = objectMapper.writeValueAsString(responseBody);
                    ClientResponse clientResponse = ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(json)
                        .build();
                    return Mono.just(clientResponse);
                } catch (Exception ex) {
                    return Mono.error(ex);
                }
            })
            .build();
    }

    private WebClient createFailingWebClient() {
        return WebClient.builder()
            .exchangeFunction(clientRequest -> Mono.error(new RuntimeException("Simulated upstream connection failure")))
            .build();
    }

    @Test
    @DisplayName("Should successfully aggregate responses from all upstream services")
    void testSuccessfulAggregation() {
        List<Map<String, Object>> mockCategories = List.of(
            Map.of("id", "cat-1", "name", "Dairy"),
            Map.of("id", "cat-2", "name", "Produce")
        );
        List<String> mockTrending = List.of("milk", "bananas", "sourdough");
        Map<String, Object> mockFlags = Map.of("mobile_home_v2", true, "express_delivery", false);

        WebClient catalogClient = createMockWebClient(mockCategories, HttpStatus.OK);
        WebClient searchClient = createMockWebClient(mockTrending, HttpStatus.OK);
        WebClient flagsClient = createMockWebClient(mockFlags, HttpStatus.OK);

        UpstreamClientFactory mockFactory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return catalogClient;
            }

            @Override
            public WebClient searchClient() {
                return searchClient;
            }

            @Override
            public WebClient featureFlagClient() {
                return flagsClient;
            }
        };

        HomeAggregator aggregator = new HomeAggregator(mockFactory, properties);

        StepVerifier.create(aggregator.aggregate("usr_123", "store_99"))
            .assertNext(homeResponse -> {
                assertThat(homeResponse.status()).isEqualTo("ok");
                assertThat(homeResponse.categories()).hasSize(2);
                assertThat(homeResponse.trending()).containsExactly("milk", "bananas", "sourdough");
                assertThat(homeResponse.flags()).containsEntry("mobile_home_v2", true);
                assertThat(homeResponse.metadata())
                    .containsEntry("userId", "usr_123")
                    .containsEntry("storeId", "store_99");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should degrade gracefully when catalog service fails")
    void testGracefulDegradationOnCatalogFailure() {
        List<String> mockTrending = List.of("apples", "bread");
        Map<String, Object> mockFlags = Map.of("banner_enabled", true);

        WebClient catalogClient = createFailingWebClient();
        WebClient searchClient = createMockWebClient(mockTrending, HttpStatus.OK);
        WebClient flagsClient = createMockWebClient(mockFlags, HttpStatus.OK);

        UpstreamClientFactory mockFactory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return catalogClient;
            }

            @Override
            public WebClient searchClient() {
                return searchClient;
            }

            @Override
            public WebClient featureFlagClient() {
                return flagsClient;
            }
        };

        HomeAggregator aggregator = new HomeAggregator(mockFactory, properties);

        StepVerifier.create(aggregator.aggregate("usr_123", null))
            .assertNext(homeResponse -> {
                assertThat(homeResponse.status()).isEqualTo("ok");
                assertThat(homeResponse.categories()).isEmpty();
                assertThat(homeResponse.trending()).containsExactly("apples", "bread");
                assertThat(homeResponse.flags()).containsEntry("banner_enabled", true);
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should degrade gracefully when search service fails")
    void testGracefulDegradationOnSearchFailure() {
        List<Map<String, Object>> mockCategories = List.of(Map.of("id", "cat-1", "name", "Snacks"));
        Map<String, Object> mockFlags = Map.of("banner_enabled", false);

        WebClient catalogClient = createMockWebClient(mockCategories, HttpStatus.OK);
        WebClient searchClient = createFailingWebClient();
        WebClient flagsClient = createMockWebClient(mockFlags, HttpStatus.OK);

        UpstreamClientFactory mockFactory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return catalogClient;
            }

            @Override
            public WebClient searchClient() {
                return searchClient;
            }

            @Override
            public WebClient featureFlagClient() {
                return flagsClient;
            }
        };

        HomeAggregator aggregator = new HomeAggregator(mockFactory, properties);

        StepVerifier.create(aggregator.aggregate(null, "store_55"))
            .assertNext(homeResponse -> {
                assertThat(homeResponse.status()).isEqualTo("ok");
                assertThat(homeResponse.categories()).hasSize(1);
                assertThat(homeResponse.trending()).isEmpty();
                assertThat(homeResponse.flags()).containsEntry("banner_enabled", false);
                assertThat(homeResponse.metadata()).containsEntry("storeId", "store_55");
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should degrade gracefully when all upstreams fail")
    void testGracefulDegradationWhenAllUpstreamsFail() {
        WebClient catalogClient = createFailingWebClient();
        WebClient searchClient = createFailingWebClient();
        WebClient flagsClient = createFailingWebClient();

        UpstreamClientFactory mockFactory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return catalogClient;
            }

            @Override
            public WebClient searchClient() {
                return searchClient;
            }

            @Override
            public WebClient featureFlagClient() {
                return flagsClient;
            }
        };

        HomeAggregator aggregator = new HomeAggregator(mockFactory, properties);

        StepVerifier.create(aggregator.aggregate(null, null))
            .assertNext(homeResponse -> {
                assertThat(homeResponse.status()).isEqualTo("ok");
                assertThat(homeResponse.categories()).isEmpty();
                assertThat(homeResponse.trending()).isEmpty();
                assertThat(homeResponse.flags()).isEmpty();
                assertThat(homeResponse.metadata()).containsKey("timestamp");
            })
            .verifyComplete();
    }
}
