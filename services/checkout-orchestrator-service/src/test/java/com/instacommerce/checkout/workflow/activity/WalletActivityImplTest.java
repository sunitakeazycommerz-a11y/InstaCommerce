package com.instacommerce.checkout.workflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.checkout.dto.WalletDebitResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

class WalletActivityImplTest {

    private RestTemplate restTemplate;
    private WalletActivityImpl activity;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        activity = new WalletActivityImpl(restTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postsDeterministicOrderReferenceOnWalletDebit() {
        WalletDebitResult ok = new WalletDebitResult("wf-1:wallet:debit", true, 1500L, null);
        when(restTemplate.postForObject(eq("/wallet/debit"), any(), eq(WalletDebitResult.class)))
            .thenReturn(ok);

        WalletDebitResult result = activity.debitWallet("user-1", 1500L, "wf-1:wallet:debit", "split checkout");

        assertThat(result.debited()).isTrue();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/wallet/debit"), captor.capture(), eq(WalletDebitResult.class));
        assertThat(captor.getValue())
            .containsEntry("userId", "user-1")
            .containsEntry("amountCents", 1500L)
            .containsEntry("referenceType", "ORDER")
            .containsEntry("referenceId", "wf-1:wallet:debit");
    }

    @Test
    void returnsDeclinedResultInsteadOfThrowingOnHttp4xxBalanceRejection() {
        when(restTemplate.postForObject(eq("/wallet/debit"), any(), eq(WalletDebitResult.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Insufficient balance",
                HttpHeaders.EMPTY,
                "Insufficient wallet balance".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
            ));

        WalletDebitResult result = activity.debitWallet("user-1", 9900L, "wf-2:wallet:debit", null);

        assertThat(result.debited()).isFalse();
        assertThat(result.failureReason()).contains("409");
    }

    @Test
    @SuppressWarnings("unchecked")
    void postsRefundReferenceOnWalletCompensationCredit() {
        activity.refundWallet("user-1", 1500L, "wf-1:wallet:refund", "compensation");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/wallet/credit"), captor.capture(), eq(Void.class));
        assertThat(captor.getValue())
            .containsEntry("userId", "user-1")
            .containsEntry("amountCents", 1500L)
            .containsEntry("referenceType", "REFUND")
            .containsEntry("referenceId", "wf-1:wallet:refund");
    }
}
