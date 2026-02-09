package com.instacommerce.fulfillment.exception;

import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InvalidPickTaskStateException extends ApiException {
    public InvalidPickTaskStateException(UUID orderId, PickTaskStatus status, String action) {
        super(HttpStatus.CONFLICT, "PICK_TASK_STATE_INVALID",
            "Cannot " + action + " when pick task is " + status + " for order " + orderId);
    }
}
