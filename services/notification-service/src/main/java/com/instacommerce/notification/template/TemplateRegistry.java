package com.instacommerce.notification.template;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class TemplateRegistry {
    private final Map<String, TemplateDefinition> definitions = Map.of(
        "OrderPlaced", new TemplateDefinition("order_confirmed",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order confirmed"),
        "OrderPacked", new TemplateDefinition("order_packed",
            List.of(NotificationChannel.SMS), "Order packed"),
        "OrderDispatched", new TemplateDefinition("order_dispatched",
            List.of(NotificationChannel.SMS, NotificationChannel.PUSH), "Out for delivery"),
        "OrderDelivered", new TemplateDefinition("order_delivered",
            List.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "Order delivered"),
        "PaymentRefunded", new TemplateDefinition("payment_refunded",
            List.of(NotificationChannel.EMAIL), "Payment refunded")
    );

    public Optional<TemplateDefinition> resolve(String eventType) {
        return Optional.ofNullable(definitions.get(eventType));
    }
}
