package com.instacommerce.wallet.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class RestOrderLookupClientTest {

    private MockWebServer mockServer;
    private RestOrderLookupClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        client = new RestOrderLookupClient(baseUrl, "wallet-loyalty-service", "test-token");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Returns OrderSnapshot on successful 200 response")
    void returnsOrderSnapshotOn200() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String body = String.format("""
            {"id":"%s","userId":"%s","status":"DELIVERED","totalCents":10000,"currency":"INR","createdAt":"%s"}
            """, orderId, userId, Instant.now().toString());

        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        Optional<OrderSnapshot> result = client.findOrder(orderId);

        assertThat(result).isPresent();
        assertThat(result.get().orderId()).isEqualTo(orderId);
        assertThat(result.get().userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("Returns empty on 404 Not Found (true not-found)")
    void returnsEmptyOn404() {
        mockServer.enqueue(new MockResponse().setResponseCode(404));

        Optional<OrderSnapshot> result = client.findOrder(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Propagates 500 server error for retry")
    void propagates500ForRetry() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        assertThatThrownBy(() -> client.findOrder(UUID.randomUUID()))
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    @DisplayName("Propagates 503 service unavailable for retry")
    void propagates503ForRetry() {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        assertThatThrownBy(() -> client.findOrder(UUID.randomUUID()))
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    @DisplayName("Propagates 400 client error (non-404) for investigation")
    void propagates400NonNotFound() {
        mockServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        assertThatThrownBy(() -> client.findOrder(UUID.randomUUID()))
            .isInstanceOf(HttpClientErrorException.class);
    }
}
