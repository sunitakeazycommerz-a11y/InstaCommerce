package com.instacommerce.admingateway.config;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class UpstreamRestClientConfigTest {

    @Test
    void attachesInternalServiceAuthHeadersViaInterceptor() {
        UpstreamRestClientConfig config = new UpstreamRestClientConfig();
        RestTemplate restTemplate = config.restTemplate(1500, 2000, "admin-gateway-service", "secret-token");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("http://order-service:8085/actuator/health"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Service", "admin-gateway-service"))
            .andExpect(header("X-Internal-Token", "secret-token"))
            .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        restTemplate.getForObject("http://order-service:8085/actuator/health", String.class);
        server.verify();
    }
}
