package com.instacommerce.wallet.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("PaymentEventConsumer conditional bean creation")
class PaymentEventConsumerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(StubDependencies.class, PaymentEventConsumer.class);

    @Nested
    @DisplayName("when wallet.consumer.payment-refund-enabled is true")
    class Enabled {

        @Test
        @DisplayName("creates PaymentEventConsumer bean")
        void beanIsPresent() {
            runner.withPropertyValues("wallet.consumer.payment-refund-enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(PaymentEventConsumer.class));
        }
    }

    @Nested
    @DisplayName("when wallet.consumer.payment-refund-enabled is false (default)")
    class Disabled {

        @Test
        @DisplayName("does not create PaymentEventConsumer bean")
        void beanIsAbsent() {
            runner.withPropertyValues("wallet.consumer.payment-refund-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PaymentEventConsumer.class));
        }
    }

    @Nested
    @DisplayName("when wallet.consumer.payment-refund-enabled is not set")
    class NotSet {

        @Test
        @DisplayName("does not create PaymentEventConsumer bean (safe default)")
        void beanIsAbsentByDefault() {
            runner.run(ctx -> assertThat(ctx).doesNotHaveBean(PaymentEventConsumer.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StubDependencies {

        @Bean
        WalletService walletService() {
            return org.mockito.Mockito.mock(WalletService.class);
        }

        @Bean
        OrderLookupClient orderLookupClient() {
            return org.mockito.Mockito.mock(OrderLookupClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
