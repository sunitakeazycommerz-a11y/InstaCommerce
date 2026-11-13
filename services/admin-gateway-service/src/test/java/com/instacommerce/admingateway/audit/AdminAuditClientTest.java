package com.instacommerce.admingateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class AdminAuditClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private SimpleMeterRegistry meterRegistry;
    private AdminAuditClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        meterRegistry = new SimpleMeterRegistry();
        client = new AdminAuditClient(
            restTemplate,
            "http://audit-trail-service:8096",
            true,
            "admin-gateway-service",
            "test-internal-service-token",
            meterRegistry);
    }

    @Test
    void publishesAuditEventWithInternalServiceAuthAndCorrelationHeaders() {
        UUID actorId = UUID.randomUUID();
        mockServer.expect(requestTo("http://audit-trail-service:8096/audit/events"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
            .andExpect(header("X-Internal-Service", "admin-gateway-service"))
            .andExpect(header("X-Internal-Token", "test-internal-service-token"))
            .andExpect(header("X-Correlation-ID", "corr-123"))
            .andExpect(jsonPath("$.eventType").value("ADMIN_MUTATION"))
            .andExpect(jsonPath("$.sourceService").value("admin-gateway-service"))
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))
            .andExpect(jsonPath("$.action").value("POST"))
            .andRespond(withCreatedEntity(URI.create("http://audit-trail-service:8096/audit/events/1")));

        client.publishAuditEvent(
            "ADMIN_MUTATION",
            actorId,
            "ADMIN",
            "flags",
            "feature-a",
            "POST",
            Map.of("status", 200),
            "198.51.100.10",
            "AdminUI/1.0",
            "corr-123");

        mockServer.verify();
        assertThat(meterRegistry.get("admin.audit.failures").counter().count()).isZero();
    }

    @Test
    void incrementsFailureCounterWhenAuditServiceReturnsServerError() {
        mockServer.expect(requestTo("http://audit-trail-service:8096/audit/events"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError());

        client.publishAuditEvent(
            "ADMIN_MUTATION",
            UUID.randomUUID(),
            "ADMIN",
            "flags",
            "feature-a",
            "POST",
            Map.of(),
            "198.51.100.10",
            "AdminUI/1.0",
            "corr-456");

        mockServer.verify();
        assertThat(meterRegistry.get("admin.audit.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementsFailureCounterWhenAuditServiceIsUnreachable() {
        mockServer.expect(requestTo("http://audit-trail-service:8096/audit/events"))
            .andRespond(request -> {
                throw new ResourceAccessException("Connection refused");
            });

        client.publishAuditEvent(
            "ADMIN_MUTATION",
            UUID.randomUUID(),
            "ADMIN",
            "flags",
            "feature-a",
            "POST",
            Map.of(),
            "198.51.100.10",
            "AdminUI/1.0",
            null);

        mockServer.verify();
        assertThat(meterRegistry.get("admin.audit.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void skipsSendingAndDoesNotIncrementFailuresWhenDisabled() {
        AdminAuditClient disabledClient = new AdminAuditClient(
            restTemplate,
            "http://audit-trail-service:8096",
            false,
            "admin-gateway-service",
            "test-internal-service-token",
            meterRegistry);

        disabledClient.publishAuditEvent(
            "ADMIN_MUTATION",
            UUID.randomUUID(),
            "ADMIN",
            "flags",
            "feature-a",
            "POST",
            Map.of(),
            "198.51.100.10",
            "AdminUI/1.0",
            "corr-789");

        mockServer.verify();
        assertThat(meterRegistry.get("admin.audit.failures").counter().count()).isZero();
    }
}
