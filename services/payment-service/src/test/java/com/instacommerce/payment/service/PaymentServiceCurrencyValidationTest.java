package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceCurrencyValidationTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentTransactionHelper txHelper;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentGateway, txHelper, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when currency is null")
    void throwsWhenCurrencyIsNull() {
        AuthorizeRequest request = new AuthorizeRequest(UUID.randomUUID(), 1000L, null, "idem-1", "pm_card");
        assertThatThrownBy(() -> paymentService.authorize(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency is mandatory");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when currency is blank")
    void throwsWhenCurrencyIsBlank() {
        AuthorizeRequest request = new AuthorizeRequest(UUID.randomUUID(), 1000L, "   ", "idem-2", "pm_card");
        assertThatThrownBy(() -> paymentService.authorize(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency is mandatory");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when currency is unsupported")
    void throwsWhenCurrencyIsUnsupported() {
        AuthorizeRequest request = new AuthorizeRequest(UUID.randomUUID(), 1000L, "INVALID_CODE", "idem-3", "pm_card");
        assertThatThrownBy(() -> paymentService.authorize(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported currency");
    }

    @Test
    @DisplayName("Authorizes successfully when valid currency is provided")
    void authorizesWithValidCurrency() {
        UUID orderId = UUID.randomUUID();
        AuthorizeRequest request = new AuthorizeRequest(orderId, 2500L, "INR", "idem-4", "pm_card");

        Payment pending = new Payment();
        pending.setId(UUID.randomUUID());
        pending.setOrderId(orderId);
        pending.setAmountCents(2500L);
        pending.setCurrency("INR");
        pending.setStatus(PaymentStatus.AUTHORIZE_PENDING);

        Payment authorized = new Payment();
        authorized.setId(pending.getId());
        authorized.setOrderId(orderId);
        authorized.setAmountCents(2500L);
        authorized.setCurrency("INR");
        authorized.setStatus(PaymentStatus.AUTHORIZED);
        authorized.setPspReference("psp-ref-123");

        when(txHelper.savePendingAuthorization(any(), anyString())).thenReturn(pending);
        when(paymentGateway.authorize(any())).thenReturn(GatewayAuthResult.success("psp-ref-123"));
        when(txHelper.completeAuthorization(any(), anyString())).thenReturn(authorized);

        PaymentResponse response = paymentService.authorize(request);
        assertThat(response).isNotNull();
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.status()).isEqualTo("AUTHORIZED");
    }
}
