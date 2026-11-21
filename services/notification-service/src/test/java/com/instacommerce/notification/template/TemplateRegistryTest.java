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
        assertThat(registry.resolve("OrderFailed")).isPresent();
        assertThat(registry.resolve("OrderFailed").get().channels())
            .contains(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH);
        assertThat(registry.resolve("SagaCompensationFailed")).isPresent();
        assertThat(registry.resolve("SagaCompensationFailed").get().channels())
            .contains(NotificationChannel.SMS, NotificationChannel.PUSH);
    }

    @Test
    @DisplayName("Resolves ItemSubstituted, PaymentRefundFailed, TierUpgraded, and AccountLocked templates")
    void resolvesExpandedLifecycleTemplates() {
        TemplateDefinition substituted = registry.resolve("ItemSubstituted").orElseThrow();
        assertThat(substituted.templateId()).isEqualTo("item_substituted");
        assertThat(substituted.channels()).containsExactly(
            NotificationChannel.PUSH, NotificationChannel.SMS, NotificationChannel.EMAIL);

        TemplateDefinition refundFailed = registry.resolve("PaymentRefundFailed").orElseThrow();
        assertThat(refundFailed.templateId()).isEqualTo("payment_refund_failed");
        assertThat(refundFailed.channels()).containsExactly(
            NotificationChannel.EMAIL, NotificationChannel.SMS);

        TemplateDefinition tierUpgraded = registry.resolve("TierUpgraded").orElseThrow();
        assertThat(tierUpgraded.templateId()).isEqualTo("tier_upgraded");
        assertThat(tierUpgraded.channels()).containsExactly(
            NotificationChannel.PUSH, NotificationChannel.EMAIL);

        TemplateDefinition accountLocked = registry.resolve("AccountLocked").orElseThrow();
        assertThat(accountLocked.templateId()).isEqualTo("account_locked");
        assertThat(accountLocked.channels()).containsExactly(
            NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    @Test
    @DisplayName("Returns empty optional for unknown event")
    void returnsEmptyForUnknownEvent() {
        assertThat(registry.resolve("NonExistentEvent")).isEmpty();
    }
}
