package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.statemachine.OrderStateMachine;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTransitionTest {

    @Test
    @DisplayName("Terminal states are correctly identified")
    void terminalStatesAreCorrectlyIdentified() {
        assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.FAILED.isTerminal()).isTrue();

        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
        assertThat(OrderStatus.PLACED.isTerminal()).isFalse();
        assertThat(OrderStatus.PACKING.isTerminal()).isFalse();
        assertThat(OrderStatus.PACKED.isTerminal()).isFalse();
        assertThat(OrderStatus.OUT_FOR_DELIVERY.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED", "FAILED"})
    @DisplayName("Transitions from terminal states are unconditionally rejected with 409 Conflict")
    void transitionsFromTerminalStatesAreRejected(OrderStatus terminalStatus) {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(terminalStatus.canTransitionTo(target)).isFalse();
            assertThatThrownBy(() -> OrderStateMachine.validate(terminalStatus, target))
                .isInstanceOf(IllegalOrderStateTransitionException.class)
                .hasMessageContaining("Cannot transition order from " + terminalStatus);
        }
    }

    @Test
    @DisplayName("Valid forward lifecycle transitions are permitted")
    void validLifecycleTransitionsArePermitted() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PLACED)).isTrue();
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.PACKING)).isTrue();
        assertThat(OrderStatus.PACKING.canTransitionTo(OrderStatus.PACKED)).isTrue();
        assertThat(OrderStatus.PACKED.canTransitionTo(OrderStatus.OUT_FOR_DELIVERY)).isTrue();
        assertThat(OrderStatus.OUT_FOR_DELIVERY.canTransitionTo(OrderStatus.DELIVERED)).isTrue();

        // Non-terminal states can transition to CANCELLED or FAILED
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PACKING.canTransitionTo(OrderStatus.FAILED)).isTrue();
    }

    @Test
    @DisplayName("Illegal backward transitions are rejected")
    void illegalBackwardTransitionsAreRejected() {
        assertThat(OrderStatus.PACKED.canTransitionTo(OrderStatus.PLACED)).isFalse();
        assertThatThrownBy(() -> OrderStateMachine.validate(OrderStatus.PACKED, OrderStatus.PLACED))
            .isInstanceOf(IllegalOrderStateTransitionException.class);
    }
}
