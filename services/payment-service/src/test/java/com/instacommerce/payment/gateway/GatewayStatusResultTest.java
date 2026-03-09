package com.instacommerce.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.payment.gateway.GatewayStatusResult.PspPaymentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GatewayStatusResultTest {

    @Nested
    @DisplayName("Boolean state predicates")
    class StatePredicates {

        @Test
        @DisplayName("isAuthorized is true only for REQUIRES_CAPTURE")
        void isAuthorized() {
            assertThat(result(PspPaymentState.REQUIRES_CAPTURE).isAuthorized()).isTrue();
            assertThat(result(PspPaymentState.SUCCEEDED).isAuthorized()).isFalse();
            assertThat(result(PspPaymentState.CANCELED).isAuthorized()).isFalse();
            assertThat(result(PspPaymentState.PROCESSING).isAuthorized()).isFalse();
        }

        @Test
        @DisplayName("isCaptured is true only for SUCCEEDED")
        void isCaptured() {
            assertThat(result(PspPaymentState.SUCCEEDED).isCaptured()).isTrue();
            assertThat(result(PspPaymentState.REQUIRES_CAPTURE).isCaptured()).isFalse();
            assertThat(result(PspPaymentState.CANCELED).isCaptured()).isFalse();
        }

        @Test
        @DisplayName("isCanceled is true only for CANCELED")
        void isCanceled() {
            assertThat(result(PspPaymentState.CANCELED).isCanceled()).isTrue();
            assertThat(result(PspPaymentState.SUCCEEDED).isCanceled()).isFalse();
            assertThat(result(PspPaymentState.REQUIRES_CAPTURE).isCanceled()).isFalse();
        }

        @Test
        @DisplayName("isInFlight is true only for PROCESSING")
        void isInFlight() {
            assertThat(result(PspPaymentState.PROCESSING).isInFlight()).isTrue();
            assertThat(result(PspPaymentState.SUCCEEDED).isInFlight()).isFalse();
        }

        @Test
        @DisplayName("isTerminal is true for SUCCEEDED and CANCELED")
        void isTerminal() {
            assertThat(result(PspPaymentState.SUCCEEDED).isTerminal()).isTrue();
            assertThat(result(PspPaymentState.CANCELED).isTerminal()).isTrue();
            assertThat(result(PspPaymentState.PROCESSING).isTerminal()).isFalse();
            assertThat(result(PspPaymentState.REQUIRES_CAPTURE).isTerminal()).isFalse();
            assertThat(result(PspPaymentState.UNKNOWN).isTerminal()).isFalse();
        }

        @Test
        @DisplayName("isFailed is true for incomplete auth states")
        void isFailed() {
            assertThat(result(PspPaymentState.REQUIRES_PAYMENT_METHOD).isFailed()).isTrue();
            assertThat(result(PspPaymentState.REQUIRES_CONFIRMATION).isFailed()).isTrue();
            assertThat(result(PspPaymentState.REQUIRES_ACTION).isFailed()).isTrue();
            assertThat(result(PspPaymentState.SUCCEEDED).isFailed()).isFalse();
            assertThat(result(PspPaymentState.CANCELED).isFailed()).isFalse();
            assertThat(result(PspPaymentState.PROCESSING).isFailed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Factory method")
    class FactoryMethod {

        @Test
        @DisplayName("of() creates result with correct fields")
        void createsWithCorrectFields() {
            GatewayStatusResult result = GatewayStatusResult.of(
                PspPaymentState.SUCCEEDED, "succeeded", 5000L);
            assertThat(result.state()).isEqualTo(PspPaymentState.SUCCEEDED);
            assertThat(result.rawStatus()).isEqualTo("succeeded");
            assertThat(result.amountCapturedCents()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("of() allows null amount")
        void allowsNullAmount() {
            GatewayStatusResult result = GatewayStatusResult.of(
                PspPaymentState.REQUIRES_CAPTURE, "requires_capture", null);
            assertThat(result.amountCapturedCents()).isNull();
        }
    }

    @Nested
    @DisplayName("All states are representable")
    class Completeness {

        @ParameterizedTest
        @EnumSource(PspPaymentState.class)
        @DisplayName("Every PspPaymentState can be used in a result")
        void everyStateProducesValidResult(PspPaymentState state) {
            GatewayStatusResult result = GatewayStatusResult.of(state, state.name(), 0L);
            assertThat(result.state()).isEqualTo(state);
        }
    }

    private static GatewayStatusResult result(PspPaymentState state) {
        return GatewayStatusResult.of(state, state.name().toLowerCase(), 0L);
    }
}
