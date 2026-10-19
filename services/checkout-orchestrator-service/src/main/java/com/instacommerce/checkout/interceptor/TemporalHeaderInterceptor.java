package com.instacommerce.checkout.interceptor;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Propagates tenant and store context (X-Tenant-Id, X-Store-Id) across Temporal
 * workflow and child activity boundaries, keeping MDC and ThreadLocal state synchronized.
 */
public class TemporalHeaderInterceptor implements ContextPropagator {

    public static final String NAME = "TemporalHeaderInterceptor";
    public static final String TENANT_ID_KEY = "X-Tenant-Id";
    public static final String STORE_ID_KEY = "X-Store-Id";

    private static final ThreadLocal<Map<String, String>> CURRENT_HEADERS = ThreadLocal.withInitial(HashMap::new);

    public static void setTenantContext(String tenantId, String storeId) {
        Map<String, String> context = new HashMap<>();
        if (tenantId != null && !tenantId.isBlank()) {
            context.put(TENANT_ID_KEY, tenantId.trim());
            MDC.put(TENANT_ID_KEY, tenantId.trim());
        }
        if (storeId != null && !storeId.isBlank()) {
            context.put(STORE_ID_KEY, storeId.trim());
            MDC.put(STORE_ID_KEY, storeId.trim());
        }
        CURRENT_HEADERS.set(context);
    }

    public static String getTenantId() {
        return CURRENT_HEADERS.get().get(TENANT_ID_KEY);
    }

    public static String getStoreId() {
        return CURRENT_HEADERS.get().get(STORE_ID_KEY);
    }

    public static void clear() {
        CURRENT_HEADERS.remove();
        MDC.remove(TENANT_ID_KEY);
        MDC.remove(STORE_ID_KEY);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
        Map<String, Payload> serialized = new HashMap<>();
        if (context instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    Payload payload = Payload.newBuilder()
                        .setData(ByteString.copyFrom(entry.getValue().toString(), StandardCharsets.UTF_8))
                        .build();
                    serialized.put(entry.getKey().toString(), payload);
                }
            }
        }
        return serialized;
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
        Map<String, String> headers = new HashMap<>();
        if (context != null) {
            for (Map.Entry<String, Payload> entry : context.entrySet()) {
                String val = entry.getValue().getData().toStringUtf8();
                headers.put(entry.getKey(), val);
            }
        }
        return headers;
    }

    @Override
    public Object getCurrentContext() {
        Map<String, String> current = new HashMap<>(CURRENT_HEADERS.get());
        String mdcTenant = MDC.get(TENANT_ID_KEY);
        if (mdcTenant != null) {
            current.put(TENANT_ID_KEY, mdcTenant);
        }
        String mdcStore = MDC.get(STORE_ID_KEY);
        if (mdcStore != null) {
            current.put(STORE_ID_KEY, mdcStore);
        }
        return current;
    }

    @Override
    public void setCurrentContext(Object context) {
        if (context instanceof Map<?, ?> map) {
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            CURRENT_HEADERS.set(headers);
            if (headers.containsKey(TENANT_ID_KEY)) {
                MDC.put(TENANT_ID_KEY, headers.get(TENANT_ID_KEY));
            }
            if (headers.containsKey(STORE_ID_KEY)) {
                MDC.put(STORE_ID_KEY, headers.get(STORE_ID_KEY));
            }
        } else {
            clear();
        }
    }
}
