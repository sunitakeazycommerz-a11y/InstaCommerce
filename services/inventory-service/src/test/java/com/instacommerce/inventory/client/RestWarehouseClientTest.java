package com.instacommerce.inventory.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.instacommerce.inventory.exception.StoreNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestWarehouseClientTest {

    private MockRestServiceServer server;
    private RestWarehouseClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestWarehouseClient(builder, "http://warehouse.test", "inventory-service", "secret-token", null);
    }

    @Test
    @DisplayName("isStoreOpen queries /stores/{id}/open with internal auth headers")
    void isStoreOpenQueriesWarehouseEndpoint() {
        UUID storeId = UUID.randomUUID();
        server.expect(requestTo("http://warehouse.test/stores/" + storeId + "/open"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Service", "inventory-service"))
            .andExpect(header("X-Internal-Token", "secret-token"))
            .andRespond(withSuccess("false", MediaType.APPLICATION_JSON));

        assertThat(client.isStoreOpen(storeId)).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("isStoreOpen throws StoreNotFoundException on 404 from warehouse-service")
    void isStoreOpenThrowsNotFoundOn404() {
        UUID storeId = UUID.randomUUID();
        server.expect(requestTo("http://warehouse.test/stores/" + storeId + "/open"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.isStoreOpen(storeId))
            .isInstanceOf(StoreNotFoundException.class);
        server.verify();
    }
}
