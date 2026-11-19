package com.instacommerce.admingateway.config;

import com.instacommerce.admingateway.security.CorrelationIdFilter;
import java.time.Duration;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class UpstreamRestClientConfig {

    private static final String HEADER_INTERNAL_SERVICE = "X-Internal-Service";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Bean
    public RestTemplate restTemplate(
            @Value("${admin-gateway.http.connect-timeout-ms:1500}") int connectTimeoutMs,
            @Value("${admin-gateway.http.read-timeout-ms:2000}") int readTimeoutMs,
            @Value("${internal.service.name:${spring.application.name:admin-gateway-service}}") String internalServiceName,
            @Value("${internal.service.token:}") String internalServiceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        ClientHttpRequestInterceptor internalAuthInterceptor = (request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            if (internalServiceName != null && !internalServiceName.isBlank()
                    && headers.getFirst(HEADER_INTERNAL_SERVICE) == null) {
                headers.set(HEADER_INTERNAL_SERVICE, internalServiceName);
            }
            if (internalServiceToken != null && !internalServiceToken.isBlank()
                    && headers.getFirst(HEADER_INTERNAL_TOKEN) == null) {
                headers.set(HEADER_INTERNAL_TOKEN, internalServiceToken);
            }
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()
                    && headers.getFirst(CorrelationIdFilter.HEADER_CORRELATION_ID) == null) {
                headers.set(CorrelationIdFilter.HEADER_CORRELATION_ID, correlationId);
            }
            return execution.execute(request, body);
        };
        restTemplate.setInterceptors(List.of(internalAuthInterceptor));
        return restTemplate;
    }
}
