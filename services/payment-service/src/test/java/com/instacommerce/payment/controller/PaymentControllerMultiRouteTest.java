package com.instacommerce.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.service.PaymentService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentControllerMultiRouteTest {

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaymentController controller = new PaymentController(paymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /payments/{id} with UUID routes successfully and exposes paymentReference")
    void testGetPaymentByUuidPrimaryRoute() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
            paymentId,
            "CAPTURED",
            "psp_123",
            2500L,
            "INR"
        );
        when(paymentService.get(paymentId)).thenReturn(response);

        mockMvc.perform(get("/payments/{id}", paymentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.paymentReference").value(paymentId.toString()))
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.amountCents").value(2500))
            .andExpect(jsonPath("$.currency").value("INR"));

        verify(paymentService).get(paymentId);
    }

    @Test
    @DisplayName("GET /api/payments/{id} alias routes correctly")
    void testGetPaymentByUuidApiRoute() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
            paymentId,
            "AUTHORIZED",
            "psp_456",
            1000L,
            "INR"
        );
        when(paymentService.get(paymentId)).thenReturn(response);

        mockMvc.perform(get("/api/payments/{id}", paymentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"))
            .andExpect(jsonPath("$.amountCents").value(1000));

        verify(paymentService).get(paymentId);
    }

    @Test
    @DisplayName("GET /api/v1/payments/{paymentReference} routes and resolves string PSP reference")
    void testGetPaymentByReferenceV1Route() throws Exception {
        String pspRef = "psp_ref_abcdef";
        UUID paymentId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
            paymentId,
            "CAPTURED",
            pspRef,
            5000L,
            "INR"
        );
        when(paymentService.getByReference(pspRef)).thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/{paymentReference}", pspRef))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.paymentReference").value(paymentId.toString()))
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.amountCents").value(5000))
            .andExpect(jsonPath("$.currency").value("INR"));

        verify(paymentService).getByReference(pspRef);
    }
}
