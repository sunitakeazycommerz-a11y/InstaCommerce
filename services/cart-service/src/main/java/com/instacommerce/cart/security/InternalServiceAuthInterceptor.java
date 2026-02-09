package com.instacommerce.cart.security;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Adds internal service authentication headers to outbound REST calls.
 * Defense-in-depth on top of Istio mTLS.
 */
public class InternalServiceAuthInterceptor implements ClientHttpRequestInterceptor {
    private final String serviceName;
    private final String serviceToken;

    public InternalServiceAuthInterceptor(String serviceName, String serviceToken) {
        this.serviceName = serviceName;
        this.serviceToken = serviceToken;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set("X-Internal-Service", serviceName);
        request.getHeaders().set("X-Internal-Token", serviceToken);
        return execution.execute(request, body);
    }
}
