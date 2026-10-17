package com.instacommerce.notification.template;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRegistryTest {

    private final TemplateRegistry registry = new TemplateRegistry();

    @Test
    @DisplayName("Resolves OrderCreated to order_confirmed template definition")
    void resolvesOrderCreated() {
        Optional<TemplateDefinition> def = registry.resolve("OrderCreated");
        assertThat(def).isPresent();
        assertThat(def.get().templateId()).isEqualTo("order_confirmed");
        assertThat(def.get().subject()).isEqualTo("Order received");
        assertThat(def.get().channels()).containsExactly(NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    @Test
    @DisplayName("Resolves OrderPlaced and OrderCancelled templates")
    void resolvesKnownEvents() {
        assertThat(registry.resolve("OrderPlaced")).isPresent();
        assertThat(registry.resolve("OrderCancelled")).isPresent();
        assertThat(registry.resolve("ETABreachRisk")).isPresent();
    }

    @Test
    @DisplayName("Returns empty optional for unknown event")
    void returnsEmptyForUnknownEvent() {
        assertThat(registry.resolve("NonExistentEvent")).isEmpty();
    }
}
