package com.instacommerce.wallet.controller;

import com.instacommerce.wallet.client.PaymentClient;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.dto.request.DebitRequest;
import com.instacommerce.wallet.dto.request.TopUpRequest;
import com.instacommerce.wallet.dto.response.WalletResponse;
import com.instacommerce.wallet.dto.response.WalletTransactionResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.service.WalletService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletController {
    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletService walletService;
    private final PaymentClient paymentClient;

    public WalletController(WalletService walletService, PaymentClient paymentClient) {
        this.walletService = walletService;
        this.paymentClient = paymentClient;
    }

    @GetMapping("/balance")
    public ResponseEntity<WalletResponse> getBalance(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(walletService.getBalance(userId));
    }

    /**
     * Tops up the authenticated user's wallet. Requires a valid, completed payment
     * reference from the payment-service. The payment amount must match the requested
     * top-up amount. Users can only top up their own wallet.
     */
    @PostMapping("/topup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WalletTransactionResponse> topUp(Authentication auth,
                                                            @Valid @RequestBody TopUpRequest request) {
        UUID userId = UUID.fromString(auth.getName());

        // Verify payment with payment-service before crediting wallet
        PaymentClient.PaymentResponse payment;
        try {
            payment = paymentClient.verifyPayment(request.paymentReference());
        } catch (ApiException ex) {
            log.warn("Top-up FAILED for user={}, paymentRef={}: payment verification unavailable",
                    userId, request.paymentReference());
            throw ex;
        }

        if (!payment.isCompleted()) {
            log.warn("Top-up REJECTED for user={}, paymentRef={}: payment status is '{}'",
                    userId, request.paymentReference(), payment.status());
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_NOT_COMPLETED",
                    "Payment has not been completed. Current status: " + payment.status());
        }

        if (payment.amountCents() != request.amountCents()) {
            log.warn("Top-up REJECTED for user={}, paymentRef={}: amount mismatch (requested={}, paid={})",
                    userId, request.paymentReference(), request.amountCents(), payment.amountCents());
            throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH",
                    "Top-up amount does not match payment amount. Requested: "
                            + request.amountCents() + ", Paid: " + payment.amountCents());
        }

        WalletTransactionResponse response = walletService.credit(
            userId,
            request.amountCents(),
            ReferenceType.TOPUP,
            "topup-" + request.paymentReference(),
            "Wallet top-up via payment " + request.paymentReference()
        );

        log.info("Top-up SUCCESS for user={}, paymentRef={}, amount={} cents",
                userId, request.paymentReference(), request.amountCents());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/debit")
    public ResponseEntity<WalletTransactionResponse> debit(Authentication auth,
                                                            @Valid @RequestBody DebitRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        ReferenceType refType = ReferenceType.valueOf(request.referenceType());
        WalletTransactionResponse response = walletService.debit(
            userId,
            request.amountCents(),
            refType,
            request.referenceId(),
            "Wallet debit: " + request.referenceType()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<WalletTransactionResponse>> getTransactions(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(walletService.getTransactions(userId, pageable));
    }
}
