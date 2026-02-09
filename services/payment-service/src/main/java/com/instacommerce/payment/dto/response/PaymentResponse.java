package com.instacommerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String status,
    @JsonIgnore
    String pspReference
) {
}
