package com.instacommerce.order.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCheckoutControllerTest {

    @Test
    @DisplayName("POST /checkout returns HTTP 410 Gone with CHECKOUT_MOVED payload")
    void shouldReturn410Gone() {
        LegacyCheckoutController controller = new LegacyCheckoutController();

        ResponseEntity<Map<String, String>> response = controller.checkout();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("CHECKOUT_MOVED");
        assertThat(response.getBody().get("message")).contains("checkout-orchestrator-service");
    }
}
