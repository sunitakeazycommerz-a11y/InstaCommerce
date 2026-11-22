package com.instacommerce.fulfillment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.fulfillment.dto.request.MarkDeliveryFailedRequest;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.exception.GlobalExceptionHandler;
import com.instacommerce.fulfillment.exception.TraceIdProvider;
import com.instacommerce.fulfillment.service.DeliveryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DeliveryControllerTest {

    @Mock
    private DeliveryService deliveryService;

    private DeliveryController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new DeliveryController(deliveryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new TraceIdProvider()))
            .build();
    }

    @Test
    void markDeliveryFailedDelegatesWithRiderPrincipalAndRole() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        MarkDeliveryFailedRequest request = new MarkDeliveryFailedRequest(
            "Address not found", "INVALID_ADDRESS", "Customer did not answer", riderId);
        DeliveryResponse expected = new DeliveryResponse(
            UUID.randomUUID(), orderId, "FAILED", riderId, "Vikram", 15, Instant.now(), null);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            riderId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RIDER")));

        when(deliveryService.markDeliveryFailed(eq(orderId), eq(request), eq(riderId), eq(false)))
            .thenReturn(expected);

        DeliveryResponse actual = controller.markDeliveryFailed(orderId, request, riderId.toString(), auth);

        assertThat(actual).isEqualTo(expected);
        verify(deliveryService).markDeliveryFailed(orderId, request, riderId, false);
    }

    @Test
    void markDeliveryFailedRecognizesPrivilegedInternalServiceRole() {
        UUID orderId = UUID.randomUUID();
        MarkDeliveryFailedRequest request = new MarkDeliveryFailedRequest("Store closed");
        DeliveryResponse expected = new DeliveryResponse(
            UUID.randomUUID(), orderId, "FAILED", null, null, 15, Instant.now(), null);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "checkout-orchestrator-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));

        when(deliveryService.markDeliveryFailed(eq(orderId), eq(request), eq(null), eq(true)))
            .thenReturn(expected);

        DeliveryResponse actual = controller.markDeliveryFailed(
            orderId, request, "checkout-orchestrator-service", auth);

        assertThat(actual).isEqualTo(expected);
        verify(deliveryService).markDeliveryFailed(orderId, request, null, true);
    }

    @Test
    void markDeliveryFailedEndpointRoutesAndValidatesRequestBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        DeliveryResponse expected = new DeliveryResponse(
            deliveryId, orderId, "FAILED", riderId, "Vikram", 15, Instant.now(), null);

        when(deliveryService.markDeliveryFailed(eq(orderId), any(MarkDeliveryFailedRequest.class), eq(null), eq(false)))
            .thenReturn(expected);

        mockMvc.perform(post("/fulfillment/orders/{orderId}/failed", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Customer unreachable","failureCode":"CUSTOMER_UNREACHABLE","notes":"No answer","riderId":"%s"}
                    """.formatted(riderId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(deliveryId.toString()))
            .andExpect(jsonPath("$.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(post("/fulfillment/orders/{orderId}/failed", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(deliveryService).markDeliveryFailed(
            eq(orderId),
            eq(new MarkDeliveryFailedRequest("Customer unreachable", "CUSTOMER_UNREACHABLE", "No answer", riderId)),
            eq(null),
            eq(false));
    }

    @Test
    void trackingRejectsInvalidPrincipalUuid() {
        UUID orderId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "not-a-uuid", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThatThrownBy(() -> controller.tracking(orderId, "not-a-uuid", auth))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(deliveryService);
    }

    @Test
    void markDeliveryFailedRejectsMalformedNonInternalPrincipalUuid() {
        UUID orderId = UUID.randomUUID();
        MarkDeliveryFailedRequest request = new MarkDeliveryFailedRequest("Customer unreachable");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "not-a-uuid", null, List.of(new SimpleGrantedAuthority("ROLE_RIDER")));

        assertThatThrownBy(() -> controller.markDeliveryFailed(orderId, request, "not-a-uuid", auth))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid subject UUID");
        verifyNoInteractions(deliveryService);
    }
}
