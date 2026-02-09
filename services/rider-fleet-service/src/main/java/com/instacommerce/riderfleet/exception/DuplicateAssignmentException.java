package com.instacommerce.riderfleet.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DuplicateAssignmentException extends ApiException {
    public DuplicateAssignmentException(UUID orderId) {
        super(HttpStatus.CONFLICT, "ASSIGNMENT_DUPLICATE", "Order already assigned: " + orderId);
    }
}
