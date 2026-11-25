package com.instacommerce.payment.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.service.PaymentService;
import com.instacommerce.payment.webhook.WebhookEventHandler;
import com.instacommerce.payment.webhook.WebhookSignatureVerifier;
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

    @Test
    @DisplayName("GET /payments/{id} returns userId and purpose metadata fields")
    void testGetPaymentIncludesUserIdAndPurpose() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
            paymentId,
            "CAPTURED",
            "psp_789",
            2500L,
            "INR",
            userId,
            "WALLET_TOPUP"
        );
        when(paymentService.get(paymentId)).thenReturn(response);

        mockMvc.perform(get("/payments/{id}", paymentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.purpose").value("WALLET_TOPUP"));
    }

    @Test
    @DisplayName("WebhookController accepts /payments/webhook, /api/payments/webhook, and /api/v1/payments/webhook")
    void testWebhookRouteParity() throws Exception {
        WebhookSignatureVerifier verifier = mock(WebhookSignatureVerifier.class);
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        when(verifier.verify("{\"id\":\"evt_1\"}", "t=1,v1=sig")).thenReturn(true);
        MockMvc webhookMvc = MockMvcBuilders.standaloneSetup(new WebhookController(verifier, handler)).build();

        for (String path : new String[]{"/payments/webhook", "/api/payments/webhook", "/api/v1/payments/webhook"}) {
            webhookMvc.perform(post(path)
                    .header("Stripe-Signature", "t=1,v1=sig")
                    .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
        }

        verify(handler, times(3)).handle("{\"id\":\"evt_1\"}");
    }
}
