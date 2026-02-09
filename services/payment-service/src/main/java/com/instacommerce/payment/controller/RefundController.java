package com.instacommerce.payment.controller;

import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.service.RefundService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class RefundController {
    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/{id}/refund")
    public RefundResponse refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
        return refundService.refund(id, request);
    }
}
