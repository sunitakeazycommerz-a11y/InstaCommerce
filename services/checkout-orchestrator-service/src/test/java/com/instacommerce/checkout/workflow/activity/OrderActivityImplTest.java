package com.instacommerce.checkout.workflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OrderActivityImplTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private OrderActivityImpl activity;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        activity = new OrderActivityImpl(restTemplate);
    }

    @Test
    void createOrderUsesWorkflowIdForDeterministicIdempotencyKeyAcrossRetries() {
        ActivityExecutionContext context = mock(ActivityExecutionContext.class);
        ActivityInfo info = mock(ActivityInfo.class);
        when(context.getInfo()).thenReturn(info);
        when(info.getWorkflowId()).thenReturn("wf-checkout-123");

        OrderCreateRequest requestWithoutKey = new OrderCreateRequest(
            "user-1",
            "store-1",
            List.of(new CartItem("prod-1", 1, 5000L)),
            5000L,
            0L,
            0L,
            5000L,
            "INR",
            null,
            "res-1",
            "pay-1",
            "addr-1",
            "pm-1",
            "quote-1",
            "token-1"
        );

        server.expect(twice(), requestTo("/api/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.idempotencyKey").value("wf-checkout-123:order:create"))
            .andRespond(withSuccess("{\"orderId\":\"ord-1\",\"estimatedDeliveryMinutes\":12}", MediaType.APPLICATION_JSON));

        try (MockedStatic<Activity> activityMock = mockStatic(Activity.class)) {
            activityMock.when(Activity::getExecutionContext).thenReturn(context);

            OrderCreationResult first = activity.createOrder(requestWithoutKey);
            OrderCreationResult second = activity.createOrder(requestWithoutKey);

            assertThat(first.orderId()).isEqualTo("ord-1");
            assertThat(second.orderId()).isEqualTo("ord-1");
        }

        server.verify();
    }

    @Test
    void createOrderPreservesExplicitDeterministicIdempotencyKey() {
        OrderCreateRequest requestWithKey = new OrderCreateRequest(
            "user-1",
            "store-1",
            List.of(new CartItem("prod-1", 1, 5000L)),
            5000L,
            0L,
            0L,
            5000L,
            "INR",
            null,
            "res-1",
            "pay-1",
            "addr-1",
            "pm-1",
            "quote-1",
            "token-1",
            "wf-explicit:order:create"
        );

        server.expect(requestTo("/api/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.idempotencyKey").value("wf-explicit:order:create"))
            .andRespond(withSuccess("{\"orderId\":\"ord-2\",\"estimatedDeliveryMinutes\":15}", MediaType.APPLICATION_JSON));

        OrderCreationResult result = activity.createOrder(requestWithKey);

        assertThat(result.orderId()).isEqualTo("ord-2");
        server.verify();
    }

    @Test
    void confirmOrderPostsPlacedStatusWithIdempotencyKeyHeader() {
        server.expect(requestTo("/admin/orders/ord-99/status"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
            .andExpect(header("Idempotency-Key", "wf-99:order:confirm"))
            .andExpect(jsonPath("$.status").value("PLACED"))
            .andExpect(jsonPath("$.note").value("Payment captured"))
            .andRespond(withNoContent());

        activity.confirmOrder("ord-99", "wf-99:order:confirm");

        server.verify();
    }
}
