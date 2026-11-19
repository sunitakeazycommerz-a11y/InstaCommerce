package com.instacommerce.mobilebff.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.controller.MobileBffController;
import com.instacommerce.mobilebff.dto.HomeResponse;
import com.instacommerce.mobilebff.security.CorrelationIdWebFilter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MobileBffResilienceAndValidationTest {

    @Test
    @DisplayName("HomeAggregator exposes partial degradation metadata and propagates X-Correlation-Id downstream")
    void partialDegradationMetadataAndCorrelationIdPropagation() {
        MobileBffProperties properties = new MobileBffProperties();
        AtomicReference<String> searchCorrelationHeader = new AtomicReference<>();

        WebClient failingCatalog = WebClient.builder()
                .baseUrl("http://catalog-service:8082")
                .exchangeFunction(req -> Mono.error(new RuntimeException("catalog timeout")))
                .build();
        WebClient okSearch = WebClient.builder()
                .baseUrl("http://search-service:8088")
                .exchangeFunction(req -> {
                    searchCorrelationHeader.set(req.headers().getFirst(CorrelationIdWebFilter.HEADER_CORRELATION_ID));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("[\"milk\",\"bread\"]")
                            .build());
                })
                .build();
        WebClient okFlags = WebClient.builder()
                .baseUrl("http://feature-flag-service:8098")
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"express\":true}")
                        .build()))
                .build();

        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return failingCatalog;
            }

            @Override
            public WebClient searchClient() {
                return okSearch;
            }

            @Override
            public WebClient featureFlagClient() {
                return okFlags;
            }
        };

        HomeAggregator aggregator = new HomeAggregator(factory, properties);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/bff/mobile/v1/home")
                        .header(CorrelationIdWebFilter.HEADER_CORRELATION_ID, "corr-mobile-777"));

        AtomicReference<HomeResponse> captured = new AtomicReference<>();
        CorrelationIdWebFilter filter = new CorrelationIdWebFilter();
        StepVerifier.create(filter.filter(exchange, ex ->
                        aggregator.aggregate("user-1", "store-blr-01")
                                .doOnNext(captured::set)
                                .then()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_CORRELATION_ID))
                .isEqualTo("corr-mobile-777");
        assertThat(searchCorrelationHeader.get()).isEqualTo("corr-mobile-777");

        HomeResponse response = captured.get();
        assertThat(response).isNotNull();
        assertThat(response.categories()).isEmpty();
        assertThat(response.trending()).containsExactly("milk", "bread");
        assertThat(response.flags()).containsEntry("express", true);
        assertThat(response.metadata()).containsEntry("degraded", true);
        assertThat(response.metadata()).containsEntry("storeId", "store-blr-01");
        assertThat(response.metadata().get("degradedServices")).isEqualTo(List.of("catalogService"));
    }

    @Test
    @DisplayName("MobileBffController validates storeId characters and trims valid storeId")
    void controllerValidatesStoreIdFormat() {
        MobileBffProperties properties = new MobileBffProperties();
        WebClient emptyOk = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("[]")
                        .build()))
                .build();
        WebClient emptyMap = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{}")
                        .build()))
                .build();
        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), properties) {
            @Override
            public WebClient catalogClient() {
                return emptyOk;
            }

            @Override
            public WebClient searchClient() {
                return emptyOk;
            }

            @Override
            public WebClient featureFlagClient() {
                return emptyMap;
            }
        };
        MobileBffController controller = new MobileBffController(new HomeAggregator(factory, properties));
        var auth = new UsernamePasswordAuthenticationToken(
                "user-1", "tok", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertThatThrownBy(() -> controller.home("../admin/secret", auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid storeId");

        StepVerifier.create(controller.home("  store-blr-01  ", auth))
                .assertNext(resp -> assertThat(resp.metadata()).containsEntry("storeId", "store-blr-01"))
                .verifyComplete();
    }
}
