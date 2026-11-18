package com.instacommerce.fulfillment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

class RestInventoryClientTest {

    @Test
    void releaseAndReserveUseExplicitMethodsWithPositiveQuantityValidation() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestInventoryClient client = new RestInventoryClient(restTemplate, "http://inventory-service:8083");
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        assertThatThrownBy(() -> client.releaseStock(productId, storeId, 0, "REASON", "ref"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.reserveOrDeductStock(productId, storeId, -2, "REASON", "ref"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(restTemplate);

        client.releaseStock(productId, storeId, 3, "RELEASE_REASON", "ref-rel");
        client.reserveOrDeductStock(productId, storeId, 2, "DEDUCT_REASON", "ref-ded");

        ArgumentCaptor<InventoryAdjustRequest> captor = ArgumentCaptor.forClass(InventoryAdjustRequest.class);
        verify(restTemplate, times(2))
            .postForObject(eq("http://inventory-service:8083/inventory/adjust"), captor.capture(), eq(Object.class));

        assertThat(captor.getAllValues().get(0).delta()).isEqualTo(3);
        assertThat(captor.getAllValues().get(1).delta()).isEqualTo(-2);
    }
}
