package com.instacommerce.fulfillment.dto.response;

import java.util.UUID;

public record BatchToteAssignment(
    UUID orderId,
    String toteBarcode,
    String toteColor
) {
}
