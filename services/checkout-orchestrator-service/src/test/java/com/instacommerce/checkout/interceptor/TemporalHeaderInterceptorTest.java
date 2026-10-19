package com.instacommerce.checkout.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.api.common.v1.Payload;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TemporalHeaderInterceptorTest {

    private TemporalHeaderInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TemporalHeaderInterceptor();
        TemporalHeaderInterceptor.clear();
    }

    @AfterEach
    void tearDown() {
        TemporalHeaderInterceptor.clear();
    }

    @Test
    @DisplayName("Serializes and deserializes tenant context across workflow activity boundaries")
    void serializesAndDeserializesTenantContext() {
        TemporalHeaderInterceptor.setTenantContext("tenant-apac-1", "store-mum-42");

        assertThat(TemporalHeaderInterceptor.getTenantId()).isEqualTo("tenant-apac-1");
        assertThat(TemporalHeaderInterceptor.getStoreId()).isEqualTo("store-mum-42");
        assertThat(MDC.get(TemporalHeaderInterceptor.TENANT_ID_KEY)).isEqualTo("tenant-apac-1");
        assertThat(MDC.get(TemporalHeaderInterceptor.STORE_ID_KEY)).isEqualTo("store-mum-42");

        Object currentContext = interceptor.getCurrentContext();
        Map<String, Payload> serialized = interceptor.serializeContext(currentContext);

        assertThat(serialized).containsKey(TemporalHeaderInterceptor.TENANT_ID_KEY);
        assertThat(serialized).containsKey(TemporalHeaderInterceptor.STORE_ID_KEY);

        // Simulate activity worker thread on a different thread
        TemporalHeaderInterceptor.clear();
        assertThat(TemporalHeaderInterceptor.getTenantId()).isNull();

        Object deserialized = interceptor.deserializeContext(serialized);
        interceptor.setCurrentContext(deserialized);

        assertThat(TemporalHeaderInterceptor.getTenantId()).isEqualTo("tenant-apac-1");
        assertThat(TemporalHeaderInterceptor.getStoreId()).isEqualTo("store-mum-42");
        assertThat(MDC.get(TemporalHeaderInterceptor.TENANT_ID_KEY)).isEqualTo("tenant-apac-1");
        assertThat(MDC.get(TemporalHeaderInterceptor.STORE_ID_KEY)).isEqualTo("store-mum-42");
    }

    @Test
    @DisplayName("Clearing context removes both ThreadLocal and MDC values")
    void clearingContextRemovesValues() {
        TemporalHeaderInterceptor.setTenantContext("tenant-99", "store-100");
        TemporalHeaderInterceptor.clear();

        assertThat(TemporalHeaderInterceptor.getTenantId()).isNull();
        assertThat(TemporalHeaderInterceptor.getStoreId()).isNull();
        assertThat(MDC.get(TemporalHeaderInterceptor.TENANT_ID_KEY)).isNull();
        assertThat(MDC.get(TemporalHeaderInterceptor.STORE_ID_KEY)).isNull();
    }
}
