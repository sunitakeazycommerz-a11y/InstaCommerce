package com.instacommerce.payment.controller;

import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
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
    public PaymentResponse capture(@PathVariable UUID id) {
        return paymentService.capture(id);
    }

    @PostMapping("/{id}/void")
    public PaymentResponse voidAuth(@PathVariable UUID id) {
        return paymentService.voidAuth(id);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.get(id);
    }
}
