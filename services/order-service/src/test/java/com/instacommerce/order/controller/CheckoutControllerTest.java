package com.instacommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.config.TemporalProperties;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.dto.request.CheckoutRequest;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.service.RateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    @Mock
    private ObjectProvider<io.temporal.client.WorkflowClient> workflowClientProvider;

    @Mock
    private RateLimitService rateLimitService;

    @Test
    void rejectsLegacyCheckoutWhenDirectSagaDisabled() {
        OrderProperties orderProperties = new OrderProperties();
        orderProperties.getCheckout().setDirectSagaEnabled(false);
        CheckoutController controller = new CheckoutController(
            workflowClientProvider,
            new TemporalProperties(),
            orderProperties,
            rateLimitService,
            new SimpleMeterRegistry());
        UUID userId = UUID.randomUUID();
        CheckoutRequest request = new CheckoutRequest(
            userId,
            "store-1",
            List.of(new CartItem(UUID.randomUUID(), "Bananas", "BNN-1", 1, 100L, 100L)),
            100L,
            0L,
            100L,
            "INR",
            null,
            "idem-1",
            "123 Main St");

        assertThatThrownBy(() -> controller.checkout(request, userId.toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiException = (ApiException) ex;
                assertThat(apiException.getStatus()).isEqualTo(HttpStatus.GONE);
                assertThat(apiException.getCode()).isEqualTo("CHECKOUT_MOVED");
            });

        verifyNoInteractions(rateLimitService, workflowClientProvider);
    }
}
