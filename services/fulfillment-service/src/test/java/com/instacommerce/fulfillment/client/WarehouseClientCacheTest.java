package com.instacommerce.fulfillment.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.fulfillment.config.CacheConfig;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = WarehouseClientCacheTest.TestConfig.class)
@TestPropertySource(properties = {
    "spring.application.name=fulfillment-service",
    "internal.service.token=test-internal-service-token"
})
class WarehouseClientCacheTest {

    private static HttpServer server;
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static volatile String responseBody = "{\"latitude\":12.9716,\"longitude\":77.5946}";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stores/", exchange -> {
            REQUEST_COUNT.incrementAndGet();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Configuration
    @EnableCaching
    @Import({CacheConfig.class, WarehouseClient.class})
    static class TestConfig {
        @Bean
        @Primary
        FulfillmentProperties fulfillmentProperties() {
            FulfillmentProperties props = new FulfillmentProperties();
            props.getClients().getWarehouse().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            return props;
        }
    }

    @Autowired
    private WarehouseClient warehouseClient;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        REQUEST_COUNT.set(0);
        if (cacheManager.getCache("storeCoordinates") != null) {
            cacheManager.getCache("storeCoordinates").clear();
        }
    }

    @Test
    void cachesNonNullCoordinatesWithCaffeineAndDoesNotCacheNullFallbacks() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        UUID storeId = UUID.randomUUID();

        responseBody = "{}";
        assertThat(warehouseClient.getStoreCoordinates(storeId)).isNull();
        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(cacheManager.getCache("storeCoordinates").get(storeId)).isNull();

        responseBody = "{\"latitude\":12.9716,\"longitude\":77.5946}";
        StoreCoordinates firstLive = warehouseClient.getStoreCoordinates(storeId);
        StoreCoordinates secondCached = warehouseClient.getStoreCoordinates(storeId);

        assertThat(firstLive).isEqualTo(new StoreCoordinates(new BigDecimal("12.9716"), new BigDecimal("77.5946")));
        assertThat(secondCached).isEqualTo(firstLive);
        assertThat(REQUEST_COUNT.get()).isEqualTo(2);
    }
}
