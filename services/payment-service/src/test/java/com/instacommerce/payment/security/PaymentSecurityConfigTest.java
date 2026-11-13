package com.instacommerce.payment.security;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.payment.config.SecurityConfig;
import com.instacommerce.payment.exception.TraceIdProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(PaymentSecurityConfigTest.TestConfig.class)
class PaymentSecurityConfigTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Configuration
    @EnableWebMvc
    @Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        TraceIdProvider.class
    })
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Bean
        JwtService jwtService() {
            return mock(JwtService.class);
        }

        @Bean
        InternalServiceAuthFilter internalServiceAuthFilter() {
            return new InternalServiceAuthFilter(INTERNAL_TOKEN, Map.of());
        }

        @RestController
        static class StubActuatorController {

            @GetMapping("/actuator/health")
            Map<String, String> health() {
                return Map.of("status", "UP");
            }

            @GetMapping("/actuator/prometheus")
            String prometheus() {
                return "jvm_memory_used_bytes 1";
            }

            @GetMapping("/actuator/metrics/http.server.requests")
            Map<String, String> metrics() {
                return Map.of("name", "http.server.requests");
            }

            @GetMapping("/actuator/info")
            Map<String, String> info() {
                return Map.of("app", "payment-service");
            }
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void actuatorMetricsRequiresInternalServiceOrAdmin() throws Exception {
        mockMvc.perform(get("/actuator/metrics/http.server.requests"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/metrics/http.server.requests")
                .with(user("customer-1").roles("USER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics/http.server.requests")
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN))
            .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics/http.server.requests")
                .with(user("admin-1").roles("ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void actuatorInfoRemainsRestrictedToAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info")
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/info")
                .with(user("admin-1").roles("ADMIN")))
            .andExpect(status().isOk());
    }
}
