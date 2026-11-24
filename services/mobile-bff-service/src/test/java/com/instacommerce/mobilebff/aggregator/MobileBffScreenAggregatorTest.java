package com.instacommerce.mobilebff.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.controller.MobileBffController;
import com.instacommerce.mobilebff.exception.GlobalExceptionHandler;
import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class MobileBffScreenAggregatorTest {

    private static WebClient jsonClient(String baseUrl, java.util.function.Function<
            org.springframework.web.reactive.function.client.ClientRequest,
            Mono<ClientResponse>> handler) {
        return WebClient.builder().baseUrl(baseUrl).exchangeFunction(handler::apply).build();
    }

    private static ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    @DisplayName("GET /m/v1/products/{id} aggregates catalog, pricing, and inventory with graceful fallback")
    void productDetailsAggregatesDownstreamsAndDegradesGracefully() {
        MobileBffProperties props = new MobileBffProperties();
        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), props) {
            @Override
            public WebClient catalogClient() {
                return jsonClient("http://catalog", req -> Mono.just(okJson("{\"id\":\"prod-1\",\"name\":\"Whole Milk\"}")));
            }

            @Override
            public WebClient pricingClient() {
                return jsonClient("http://pricing", req -> Mono.just(okJson("{\"finalPriceCents\":5900}")));
            }

            @Override
            public WebClient inventoryClient() {
                return jsonClient("http://inventory", req -> Mono.error(new RuntimeException("inventory timeout")));
            }
        };

        ScreenAggregator aggregator = new ScreenAggregator(factory, props);
        MobileBffController controller = new MobileBffController(
                new HomeAggregator(factory, props),
                null,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public ScreenAggregator getObject() { return aggregator; }
                    @Override public ScreenAggregator getIfAvailable() { return aggregator; }
                    @Override public ScreenAggregator getIfUnique() { return aggregator; }
                    @Override public java.util.Iterator<ScreenAggregator> iterator() { return List.of(aggregator).iterator(); }
                });

        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalExceptionHandler(new MobileBffAccessDeniedHandler(new ObjectMapper())))
                .build();

        client.get().uri("/m/v1/products/prod-1?storeId=store-blr-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.productId").isEqualTo("prod-1")
                .jsonPath("$.product.name").isEqualTo("Whole Milk")
                .jsonPath("$.pricing.finalPriceCents").isEqualTo(5900)
                .jsonPath("$.degraded").isEqualTo(true)
                .jsonPath("$.degradedServices[0]").isEqualTo("inventoryService");
    }

    @Test
    @DisplayName("GET /m/v1/products/{id} propagates 404 when catalog returns Not Found")
    void productDetailsPropagates404() {
        MobileBffProperties props = new MobileBffProperties();
        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), props) {
            @Override
            public WebClient catalogClient() {
                return jsonClient("http://catalog", req -> Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()));
            }

            @Override
            public WebClient pricingClient() {
                return jsonClient("http://pricing", req -> Mono.just(okJson("{}")));
            }
        };
        ScreenAggregator aggregator = new ScreenAggregator(factory, props);

        assertThatThrownBy(() -> aggregator.aggregateProduct("missing-prod", null, null).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("GET /m/v1/cart/summary chains cart items into pricing and aggregates wallet & loyalty")
    void cartSummaryAggregatesCartPricingWalletAndLoyalty() {
        MobileBffProperties props = new MobileBffProperties();
        AtomicReference<String> capturedUserHeader = new AtomicReference<>();

        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), props) {
            @Override
            public WebClient cartClient() {
                return jsonClient("http://cart", req -> {
                    capturedUserHeader.set(req.headers().getFirst("X-User-Id"));
                    return Mono.just(okJson("{\"storeId\":\"store-1\",\"items\":[{\"productId\":\"p-1\",\"quantity\":2}]}"));
                });
            }

            @Override
            public WebClient pricingClient() {
                return jsonClient("http://pricing", req -> Mono.just(okJson("{\"totalCents\":12000}")));
            }

            @Override
            public WebClient walletLoyaltyClient() {
                return jsonClient("http://wallet", req -> {
                    String path = req.url().getPath();
                    if (path.contains("/wallet/balance")) {
                        return Mono.just(okJson("{\"balanceCents\":5000}"));
                    }
                    return Mono.just(okJson("{\"pointsBalance\":150}"));
                });
            }
        };

        ScreenAggregator aggregator = new ScreenAggregator(factory, props);
        Map<String, Object> summary = aggregator
                .aggregateCartSummary("usr-123", "jwt-abc", null, "SAVE10")
                .block();

        assertThat(capturedUserHeader.get()).isEqualTo("usr-123");
        assertThat(summary).isNotNull();
        assertThat(summary.get("pricing")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("totalCents", 12000);
        assertThat(summary.get("wallet")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("balanceCents", 5000);
        assertThat(summary.get("loyalty")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("pointsBalance", 150);
    }

    @Test
    @DisplayName("GET /m/v1/orders/{id}/tracking enforces BOLA ownership and aggregates fulfillment + ETA")
    void orderTrackingEnforcesOwnershipAndAggregatesTracking() {
        MobileBffProperties props = new MobileBffProperties();
        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), props) {
            @Override
            public WebClient orderClient() {
                return jsonClient("http://order", req -> Mono.just(okJson("{\"id\":\"ord-1\",\"userId\":\"usr-owner\",\"status\":\"PACKED\"}")));
            }

            @Override
            public WebClient fulfillmentClient() {
                return jsonClient("http://fulfillment", req -> Mono.just(okJson("{\"stage\":\"OUT_FOR_DELIVERY\"}")));
            }

            @Override
            public WebClient routingEtaClient() {
                return jsonClient("http://routing", req -> {
                    if (req.url().getPath().endsWith("/eta")) {
                        return Mono.just(okJson("{\"etaMinutes\":8}"));
                    }
                    return Mono.just(okJson("{\"riderId\":\"rider-7\"}"));
                });
            }
        };

        ScreenAggregator aggregator = new ScreenAggregator(factory, props);
        assertThatThrownBy(() -> aggregator.aggregateOrderTracking("ord-1", "usr-attacker", "jwt-att", false).block())
                .isInstanceOf(AccessDeniedException.class);

        Map<String, Object> tracking = aggregator
                .aggregateOrderTracking("ord-1", "usr-owner", "jwt-owner", false)
                .block();
        assertThat(tracking).isNotNull();
        assertThat(tracking.get("fulfillment")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("stage", "OUT_FOR_DELIVERY");
        assertThat(tracking.get("eta")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("etaMinutes", 8);
        assertThat(tracking.get("delivery")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("riderId", "rider-7");
    }

    @Test
    @DisplayName("POST /m/v1/checkout/submit enforces userId BOLA and forwards Idempotency-Key and Bearer token")
    void submitCheckoutEnforcesBolaAndForwardsHeaders() {
        MobileBffProperties props = new MobileBffProperties();
        AtomicReference<String> idempotencyRef = new AtomicReference<>();
        AtomicReference<String> authRef = new AtomicReference<>();

        UpstreamClientFactory factory = new UpstreamClientFactory(WebClient.builder(), props) {
            @Override
            public WebClient checkoutClient() {
                return jsonClient("http://checkout", req -> {
                    idempotencyRef.set(req.headers().getFirst("Idempotency-Key"));
                    authRef.set(req.headers().getFirst(HttpHeaders.AUTHORIZATION));
                    return Mono.just(okJson("{\"workflowId\":\"wf-99\",\"status\":\"STARTED\"}"));
                });
            }
        };

        ScreenAggregator aggregator = new ScreenAggregator(factory, props);
        Authentication customer = new UsernamePasswordAuthenticationToken(
                "usr-owner", "jwt-secret", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertThatThrownBy(() -> aggregator.submitCheckout(
                Map.of("userId", "usr-other", "paymentMethodId", "pm-1", "deliveryAddressId", "addr-1"),
                "idem-1",
                customer.getName(),
                "jwt-secret",
                false).block())
                .isInstanceOf(AccessDeniedException.class);

        Map<String, Object> result = aggregator.submitCheckout(
                Map.of("paymentMethodId", "pm-1", "deliveryAddressId", "addr-1"),
                "idem-123",
                customer.getName(),
                "jwt-secret",
                false).block();

        assertThat(result).containsEntry("workflowId", "wf-99");
        assertThat(idempotencyRef.get()).isEqualTo("idem-123");
        assertThat(authRef.get()).isEqualTo("Bearer jwt-secret");
    }
}
