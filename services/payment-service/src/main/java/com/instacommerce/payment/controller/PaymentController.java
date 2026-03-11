package com.instacommerce.payment.controller;

import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.request.CaptureRequest;
import com.instacommerce.payment.dto.request.VoidRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public PaymentResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        return paymentService.authorize(request);
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable UUID id,
                                   @Valid @RequestBody(required = false) CaptureRequest request) {
        Long amountCents = request == null ? null : request.amountCents();
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        return paymentService.capture(id, amountCents, idempotencyKey);
    }

    @PostMapping("/{id}/void")
    public PaymentResponse voidAuth(@PathVariable UUID id,
                                    @Valid @RequestBody(required = false) VoidRequest request) {
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        return paymentService.voidAuth(id, idempotencyKey);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'INTERNAL_SERVICE')")
    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.get(id);
    }
}
