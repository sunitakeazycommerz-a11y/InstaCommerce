package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartValidationResult(
    String userId,
    List<CartItem> items,
    boolean valid,
    String storeId
) {
    public static final String DEFAULT_STORE_ID = "00000000-0000-0000-0000-000000000001";

    @JsonCreator
    public CartValidationResult(
        @JsonProperty("userId") String userId,
        @JsonProperty("items") List<CartItem> items,
        @JsonProperty("valid") Boolean valid,
        @JsonProperty("storeId") String storeId
    ) {
        this(
            userId,
            items != null ? items : List.of(),
            valid != null ? valid : (items != null && !items.isEmpty()),
            storeId != null && !storeId.isBlank() ? storeId : DEFAULT_STORE_ID
        );
    }
}
