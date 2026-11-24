package com.instacommerce.wallet.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpStatusCodeException;

class PaymentClientTest {

    private MockWebServer mockServer;
    private PaymentClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        client = new PaymentClient(baseUrl, "wallet-loyalty-service", "internal-secret-token");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Returns PaymentResponse, attaches internal headers, and verifies CAPTURED status")
    void returnsPaymentResponseOnCaptured200() throws Exception {
        UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String body = """
            {
                "paymentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "paymentReference": "pay_xyz_987",
                "status": "CAPTURED",
                "amountCents": 3000,
                "currency": "INR",
                "userId": "11111111-2222-3333-4444-555555555555",
                "purpose": "WALLET_TOPUP"
            }
            """;
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body));

        PaymentClient.PaymentResponse response = client.verifyPayment("pay_xyz_987");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/v1/payments/pay_xyz_987");
        assertThat(request.getHeader("X-Internal-Service")).isEqualTo("wallet-loyalty-service");
        assertThat(request.getHeader("X-Internal-Token")).isEqualTo("internal-secret-token");
        assertThat(response).isNotNull();
        assertThat(response.paymentReference()).isEqualTo("pay_xyz_987");
        assertThat(response.status()).isEqualTo("CAPTURED");
        assertThat(response.amountCents()).isEqualTo(3000L);
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.purpose()).isEqualTo("WALLET_TOPUP");
        assertThat(response.isCompleted()).isTrue();
        assertThat(response.isTopUpEligiblePurpose()).isTrue();
    }

    @Test
    @DisplayName("isCompleted returns true only for COMPLETED and CAPTURED statuses and enforces purpose eligibility")
    void testIsCompletedVariants() {
        UUID userId = UUID.randomUUID();
        PaymentClient.PaymentResponse completed = new PaymentClient.PaymentResponse("ref-1", "COMPLETED", 1000, "INR", userId, "WALLET_TOPUP");
        PaymentClient.PaymentResponse captured = new PaymentClient.PaymentResponse("ref-2", "CAPTURED", 1000, "INR", userId, null);
        PaymentClient.PaymentResponse authorized = new PaymentClient.PaymentResponse("ref-3", "AUTHORIZED", 1000, "INR", userId, "ORDER_CHECKOUT");
        PaymentClient.PaymentResponse failed = new PaymentClient.PaymentResponse("ref-4", "FAILED", 1000, "INR");

        assertThat(completed.isCompleted()).isTrue();
        assertThat(completed.isTopUpEligiblePurpose()).isTrue();
        assertThat(captured.isCompleted()).isTrue();
        assertThat(captured.isTopUpEligiblePurpose()).isTrue();
        assertThat(authorized.isCompleted()).isFalse();
        assertThat(authorized.isTopUpEligiblePurpose()).isFalse();
        assertThat(failed.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("Throws HttpStatusCodeException when payment service returns 500 error directly")
    void throwsExceptionOnServerError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.verifyPayment("ref-err"))
            .isInstanceOf(HttpStatusCodeException.class);
    }
}
