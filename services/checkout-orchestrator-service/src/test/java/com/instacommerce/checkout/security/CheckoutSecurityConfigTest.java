package com.instacommerce.checkout.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.checkout.controller.CheckoutController;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.exception.TraceIdProvider;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import com.instacommerce.checkout.service.CheckoutIdempotencyService;
import com.instacommerce.checkout.service.CompensationReplayService;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(CheckoutSecurityConfigTest.TestConfig.class)
class CheckoutSecurityConfigTest {

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
        WorkflowClient workflowClient() {
            return mock(WorkflowClient.class);
        }

        @Bean
        CheckoutIdempotencyService idempotencyService() {
            return mock(CheckoutIdempotencyService.class);
        }

        @Bean
        CompensationDlqRepository dlqRepository() {
            return mock(CompensationDlqRepository.class);
        }

        @Bean
        CompensationReplayService compensationReplayService() {
            return mock(CompensationReplayService.class);
        }

        @Bean
        CheckoutController checkoutController(WorkflowClient workflowClient,
                                              CheckoutIdempotencyService idempotencyService,
                                              CompensationDlqRepository dlqRepository,
                                              CompensationReplayService replayService) {
            return new CheckoutController(workflowClient, "CHECKOUT_TEST_QUEUE",
                idempotencyService, dlqRepository, replayService);
        }
    }

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtService jwtService;
    @Autowired private WorkflowClient workflowClient;
    @Autowired private CheckoutIdempotencyService idempotencyService;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
    }

    @Test
    void unauthenticatedRequestsToApiV1CheckoutAndCheckoutAreRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new CheckoutRequest("user-1", "pm-1", null, "addr-1"));

        mockMvc.perform(post("/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedBearerTokenAccessesApiV1Checkout() throws Exception {
        Jws<Claims> jws = mock(Jws.class);
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("valid-jwt")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user-1");
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(jwtService).extractAuthorities(claims);

        CheckoutWorkflow workflow = mock(CheckoutWorkflow.class);
        when(idempotencyService.findValidResponse("idem-sec-1")).thenReturn(Optional.empty());
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class))).thenReturn(workflow);
        when(workflow.checkout(any(CheckoutRequest.class))).thenReturn(CheckoutResponse.success("ord-sec-1", 1000, 10));

        mockMvc.perform(post("/api/v1/checkout")
                .header("Authorization", "Bearer valid-jwt")
                .header("Idempotency-Key", "idem-sec-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CheckoutRequest("user-1", "pm-1", null, "addr-1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value("ord-sec-1"));
    }
}
