package com.instacommerce.fraud.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class RestOrderLookupClientTest {

    private MockRestServiceServer server;
    private RestOrderLookupClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestOrderLookupClient(
                builder, "http://order-service:8084", "fraud-detection-service", "secret-token");
    }

    @Test
    void returnsOrderSnapshotAndForwardsInternalAuthHeaders() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String json = """
                {"id":"%s","userId":"%s","status":"DELIVERED","totalCents":5000}
                """.formatted(orderId, userId);

        server.expect(requestTo("http://order-service:8084/admin/orders/" + orderId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service", "fraud-detection-service"))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        Optional<OrderSnapshot> result = client.findOrder(orderId);

        assertThat(result).contains(new OrderSnapshot(orderId, userId));
        server.verify();
    }

    @Test
    void returnsEmptyOn404NotFound() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("http://order-service:8084/admin/orders/" + orderId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<OrderSnapshot> result = client.findOrder(orderId);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void propagatesServerErrorsForKafkaRetry() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("http://order-service:8084/admin/orders/" + orderId))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findOrder(orderId))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        server.verify();
    }
}
