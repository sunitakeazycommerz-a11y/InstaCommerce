package com.instacommerce.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.wallet.client.PaymentClient;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.dto.response.ReferralCodeResponse;
import com.instacommerce.wallet.dto.response.WalletResponse;
import com.instacommerce.wallet.dto.response.WalletTransactionResponse;
import com.instacommerce.wallet.exception.GlobalExceptionHandler;
import com.instacommerce.wallet.security.AuthenticatedUserResolver;
import com.instacommerce.wallet.service.LoyaltyService;
import com.instacommerce.wallet.service.ReferralService;
import com.instacommerce.wallet.service.WalletService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WalletLoyaltyInternalAuthControllerTest {

    private WalletService walletService;
    private PaymentClient paymentClient;
    private LoyaltyService loyaltyService;
    private ReferralService referralService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        paymentClient = mock(PaymentClient.class);
        loyaltyService = mock(LoyaltyService.class);
        referralService = mock(ReferralService.class);

        WalletController walletController = new WalletController(walletService, paymentClient);
        LoyaltyController loyaltyController = new LoyaltyController(loyaltyService);
        ReferralController referralController = new ReferralController(referralService);

        mockMvc = MockMvcBuilders.standaloneSetup(walletController, loyaltyController, referralController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    @Test
    void shopperJwtIgnoresSpoofedXUserIdAcrossWalletLoyaltyAndReferral() throws Exception {
        UUID shopperId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        Authentication shopperAuth = new UsernamePasswordAuthenticationToken(
            shopperId.toString(),
            "jwt-token",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

        when(walletService.getBalance(shopperId))
            .thenReturn(new WalletResponse(5000L, "INR"));
        when(walletService.getTransactions(eq(shopperId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(paymentClient.verifyPayment("pay-ref-1"))
            .thenReturn(new PaymentClient.PaymentResponse("pay-ref-1", "CAPTURED", 1000L, "INR"));
        when(walletService.credit(eq(shopperId), eq(1000L), eq(ReferenceType.TOPUP), any(), any()))
            .thenReturn(new WalletTransactionResponse(
                "CREDIT", 1000L, 6000L, "TOPUP", "topup-pay-ref-1", "topup", Instant.now()));
        when(loyaltyService.getBalance(shopperId))
            .thenReturn(new LoyaltyResponse(250, "SILVER", 1200));
        when(referralService.getOrGenerateCode(shopperId))
            .thenReturn(new ReferralCodeResponse("REF123", 2, 10, 5000L));

        mockMvc.perform(get("/wallet/balance")
                .principal(shopperAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, victimId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balanceCents").value(5000));

        mockMvc.perform(get("/wallet/transactions")
                .principal(shopperAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, victimId.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/wallet/topup")
                .principal(shopperAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, victimId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amountCents":1000,"paymentReference":"pay-ref-1"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/loyalty/points")
                .principal(shopperAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, victimId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pointsBalance").value(250));

        mockMvc.perform(get("/referral/code")
                .principal(shopperAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, victimId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("REF123"));

        verify(walletService).getBalance(shopperId);
        verify(walletService).getTransactions(eq(shopperId), any(Pageable.class));
        verify(walletService).credit(eq(shopperId), eq(1000L), eq(ReferenceType.TOPUP), any(), any());
        verify(loyaltyService).getBalance(shopperId);
        verify(referralService).getOrGenerateCode(shopperId);
    }

    @Test
    void internalServiceWithValidXUserIdSucceedsOnWalletAndLoyaltyEndpoints() throws Exception {
        UUID targetUserId = UUID.randomUUID();
        String orderId = UUID.randomUUID().toString();
        Authentication internalAuth = new UsernamePasswordAuthenticationToken(
            "mobile-bff-service",
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"),
                new SimpleGrantedAuthority("ROLE_ADMIN")));

        when(walletService.getBalance(targetUserId))
            .thenReturn(new WalletResponse(8200L, "INR"));
        when(loyaltyService.getBalance(targetUserId))
            .thenReturn(new LoyaltyResponse(420, "GOLD", 2100));
        when(loyaltyService.redeemPoints(targetUserId, 100, orderId))
            .thenReturn(new LoyaltyResponse(320, "GOLD", 2100));

        mockMvc.perform(get("/wallet/balance")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, targetUserId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balanceCents").value(8200));

        mockMvc.perform(get("/loyalty/points")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, targetUserId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pointsBalance").value(420));

        mockMvc.perform(post("/loyalty/redeem")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, targetUserId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"points":100,"orderId":"%s"}
                    """.formatted(orderId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pointsBalance").value(320));

        mockMvc.perform(post("/referral/redeem")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, targetUserId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"FRIEND50"}
                    """))
            .andExpect(status().isOk());

        verify(walletService).getBalance(targetUserId);
        verify(loyaltyService).getBalance(targetUserId);
        verify(loyaltyService).redeemPoints(targetUserId, 100, orderId);
        verify(referralService).redeemReferral("FRIEND50", targetUserId);
    }

    @Test
    void internalServiceWithMissingOrInvalidXUserIdReturns400MissingUserId() throws Exception {
        Authentication internalAuth = new UsernamePasswordAuthenticationToken(
            "mobile-bff-service",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));

        mockMvc.perform(get("/wallet/balance")
                .principal(internalAuth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));

        mockMvc.perform(get("/wallet/balance")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, "not-a-valid-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));

        mockMvc.perform(get("/loyalty/points")
                .principal(internalAuth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));

        mockMvc.perform(get("/loyalty/points")
                .principal(internalAuth)
                .header(AuthenticatedUserResolver.USER_ID_HEADER, "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void adminWithUuidPrincipalFallsBackToPrincipalWhenHeaderOmitted() {
        UUID adminUserId = UUID.randomUUID();
        Authentication adminAuth = new UsernamePasswordAuthenticationToken(
            adminUserId.toString(),
            "jwt",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        UUID resolved = new AuthenticatedUserResolver().resolve(adminAuth, null);
        assertThat(resolved).isEqualTo(adminUserId);
    }
}
