package com.instacommerce.wallet.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.wallet.exception.ApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentClientTest {

    private MockWebServer mockServer;
    private PaymentClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        client = new PaymentClient(baseUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Returns PaymentResponse and verifies CAPTURED status as completed")
    void returnsPaymentResponseOnCaptured200() throws Exception {
        String body = """
            {
                "paymentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "paymentReference": "pay_xyz_987",
                "status": "CAPTURED",
                "amountCents": 3000,
                "currency": "INR"
            }
            """;
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body));

        PaymentClient.PaymentResponse response = client.verifyPayment("pay_xyz_987");

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/v1/payments/pay_xyz_987");
        assertThat(response).isNotNull();
        assertThat(response.paymentReference()).isEqualTo("pay_xyz_987");
        assertThat(response.status()).isEqualTo("CAPTURED");
        assertThat(response.amountCents()).isEqualTo(3000L);
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("isCompleted returns true for COMPLETED and AUTHORIZED statuses")
    void testIsCompletedVariants() {
        PaymentClient.PaymentResponse completed = new PaymentClient.PaymentResponse("ref-1", "COMPLETED", 1000, "INR");
        PaymentClient.PaymentResponse captured = new PaymentClient.PaymentResponse("ref-2", "CAPTURED", 1000, "INR");
        PaymentClient.PaymentResponse authorized = new PaymentClient.PaymentResponse("ref-3", "AUTHORIZED", 1000, "INR");
        PaymentClient.PaymentResponse failed = new PaymentClient.PaymentResponse("ref-4", "FAILED", 1000, "INR");

        assertThat(completed.isCompleted()).isTrue();
        assertThat(captured.isCompleted()).isTrue();
        assertThat(authorized.isCompleted()).isTrue();
        assertThat(failed.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("Throws HttpStatusCodeException when payment service returns 500 error directly")
    void throwsExceptionOnServerError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.verifyPayment("ref-err"))
            .isInstanceOf(org.springframework.web.client.HttpStatusCodeException.class);
    }
}
