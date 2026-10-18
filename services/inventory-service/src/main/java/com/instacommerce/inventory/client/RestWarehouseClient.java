package com.instacommerce.inventory.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.instacommerce.inventory.exception.StoreInactiveException;
import com.instacommerce.inventory.exception.StoreNotFoundException;
import com.instacommerce.inventory.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class RestWarehouseClient implements WarehouseClient {
    private static final Logger log = LoggerFactory.getLogger(RestWarehouseClient.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final Cache<UUID, String> storeStatusCache;

    public RestWarehouseClient(
            @org.springframework.beans.factory.annotation.Autowired(required = false) RestClient.Builder builder,
            @Value("${warehouse.service.url:http://localhost:8092}") String baseUrl,
            @Value("${internal.service.name:${spring.application.name}}") String serviceName,
            @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(2000));
        requestFactory.setReadTimeout(Duration.ofMillis(5000));

        RestClient.Builder clientBuilder = builder != null ? builder : RestClient.builder();
        this.restClient = clientBuilder
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
                .build();
        this.baseUrl = baseUrl;
        this.storeStatusCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(1000)
                .build();
    }

    @Override
    @CircuitBreaker(name = "warehouseService", fallbackMethod = "validateStoreActiveFallback")
    @Retry(name = "warehouseService")
    @Bulkhead(name = "warehouseService", fallbackMethod = "validateStoreActiveFallback")
    public void validateStoreActive(UUID storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId cannot be null");
        }
        String status = storeStatusCache.get(storeId, this::fetchStoreStatus);
        if (status == null) {
            throw new StoreNotFoundException(storeId);
        }
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            log.warn("Dark store {} is not active (current status: {})", storeId, status);
            throw new StoreInactiveException(storeId, status);
        }
    }

    @SuppressWarnings("unused")
    private void validateStoreActiveFallback(UUID storeId, Throwable e) {
        log.warn("Resilience fallback for warehouseService validateStoreActive storeId={}: {}", storeId, e.getMessage());
        if (e instanceof StoreNotFoundException notFound) {
            throw notFound;
        }
        if (e instanceof StoreInactiveException inactive) {
            throw inactive;
        }
        if (e instanceof IllegalArgumentException illegal) {
            throw illegal;
        }
        log.error("Warehouse service unavailable for storeId={}", storeId, e);
        throw new RestClientException("Warehouse service unavailable via resilience bulkhead/circuit breaker: " + e.getMessage(), e);
    }

    @Override
    public void evictStore(UUID storeId) {
        if (storeId != null) {
            storeStatusCache.invalidate(storeId);
            log.info("Evicted dark store {} from status cache", storeId);
        }
    }

    private String fetchStoreStatus(UUID storeId) {
        try {
            StoreDto store = restClient.get()
                    .uri(baseUrl + "/stores/{id}", storeId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        if (resp.getStatusCode().value() == 404) {
                            throw new StoreNotFoundException(storeId);
                        }
                    })
                    .body(StoreDto.class);

            if (store == null || store.status() == null) {
                throw new StoreNotFoundException(storeId);
            }
            return store.status();
        } catch (StoreNotFoundException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new StoreNotFoundException(storeId);
            }
            log.error("Failed to fetch dark store status for {}: {}", storeId, ex.getMessage());
            // Fallback: If warehouse service has a 5xx or network issue, fail safe or propagate
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error fetching dark store status for {}: {}", storeId, ex.getMessage());
            throw ex;
        }
    }

    record StoreDto(UUID id, String status) {}
}
