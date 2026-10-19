package com.instacommerce.notification.template;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class TemplateRegistry {
    private final Map<String, TemplateDefinition> definitions = Map.ofEntries(
        Map.entry("OrderCreated", new TemplateDefinition("order_confirmed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order received")),
        Map.entry("OrderPlaced", new TemplateDefinition("order_confirmed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order confirmed")),
        Map.entry("OrderPacked", new TemplateDefinition("order_packed",
            List.of(NotificationChannel.SMS), "Order packed")),
        Map.entry("OrderDispatched", new TemplateDefinition("order_dispatched",
            List.of(NotificationChannel.SMS, NotificationChannel.PUSH), "Out for delivery")),
        Map.entry("OrderDelivered", new TemplateDefinition("order_delivered",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order delivered")),
        Map.entry("PaymentRefunded", new TemplateDefinition("payment_refunded",
            List.of(NotificationChannel.EMAIL), "Payment refunded")),
        Map.entry("OrderCancelled", new TemplateDefinition("order_cancelled",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order cancelled")),
        Map.entry("OrderFailed", new TemplateDefinition("order_failed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH), "Order failed")),
        Map.entry("SagaCompensationFailed", new TemplateDefinition("order_failed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH), "Order processing issue")),
        Map.entry("DeliveryFailed", new TemplateDefinition("delivery_failed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH), "Delivery attempt failed")),
        Map.entry("PaymentFailed", new TemplateDefinition("payment_failed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Payment failed")),
        Map.entry("ETABreachRisk", new TemplateDefinition("eta_delayed",
            List.of(NotificationChannel.SMS, NotificationChannel.PUSH), "Delivery delayed"))
    );

    public Optional<TemplateDefinition> resolve(String eventType) {
        return Optional.ofNullable(definitions.get(eventType));
    }
}
