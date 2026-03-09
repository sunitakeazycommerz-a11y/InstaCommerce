package com.instacommerce.fulfillment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.instacommerce.fulfillment.client.OrderClient;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderStatusEventListenerTest {

    @Mock
    private OrderClient orderClient;

    private FulfillmentProperties fulfillmentProperties;
    private SimpleMeterRegistry meterRegistry;
    private OrderStatusEventListener listener;

    @BeforeEach
    void setUp() {
        fulfillmentProperties = new FulfillmentProperties();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void callbackEnabledByDefault_invokesOrderClient() {
        listener = new OrderStatusEventListener(orderClient, fulfillmentProperties, meterRegistry);
        UUID orderId = UUID.randomUUID();

        listener.handleOrderStatusUpdate(new OrderStatusUpdateEvent(orderId, "OUT_FOR_DELIVERY", "packed"));

        verify(orderClient).updateStatus(orderId, "OUT_FOR_DELIVERY", "packed");
        assertThat(skippedCount()).isZero();
    }

    @Test
    void callbackDisabled_skipsOrderClientAndIncrementsCounter() {
        fulfillmentProperties.getChoreography().setOrderStatusCallbackEnabled(false);
        listener = new OrderStatusEventListener(orderClient, fulfillmentProperties, meterRegistry);
        UUID orderId = UUID.randomUUID();

        listener.handleOrderStatusUpdate(new OrderStatusUpdateEvent(orderId, "DELIVERED", "note"));

        verifyNoInteractions(orderClient);
        assertThat(skippedCount()).isOne();
    }

    @Test
    void callbackExplicitlyEnabled_invokesOrderClient() {
        fulfillmentProperties.getChoreography().setOrderStatusCallbackEnabled(true);
        listener = new OrderStatusEventListener(orderClient, fulfillmentProperties, meterRegistry);
        UUID orderId = UUID.randomUUID();

        listener.handleOrderStatusUpdate(new OrderStatusUpdateEvent(orderId, "DELIVERED", null));

        verify(orderClient).updateStatus(orderId, "DELIVERED", null);
        assertThat(skippedCount()).isZero();
    }

    @Test
    void callbackDisabled_multipleEvents_incrementsCounterEachTime() {
        fulfillmentProperties.getChoreography().setOrderStatusCallbackEnabled(false);
        listener = new OrderStatusEventListener(orderClient, fulfillmentProperties, meterRegistry);

        listener.handleOrderStatusUpdate(new OrderStatusUpdateEvent(UUID.randomUUID(), "A", null));
        listener.handleOrderStatusUpdate(new OrderStatusUpdateEvent(UUID.randomUUID(), "B", null));

        verifyNoInteractions(orderClient);
        assertThat(skippedCount()).isEqualTo(2);
    }

    private double skippedCount() {
        return meterRegistry.counter("fulfillment.order_status_callback.skipped").count();
    }
}
