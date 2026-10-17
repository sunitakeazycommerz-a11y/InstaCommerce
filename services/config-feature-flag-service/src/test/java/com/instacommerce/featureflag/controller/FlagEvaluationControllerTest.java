package com.instacommerce.featureflag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.dto.request.BulkEvaluationRequest;
import com.instacommerce.featureflag.dto.response.BulkEvaluationResponse;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.service.BulkEvaluationService;
import com.instacommerce.featureflag.service.FlagEvaluationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = FlagEvaluationController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            com.instacommerce.featureflag.security.SecurityConfig.class,
            com.instacommerce.featureflag.security.JwtAuthenticationFilter.class
        }
    )
)
@AutoConfigureMockMvc(addFilters = false)
class FlagEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private FlagEvaluationService flagEvaluationService;

    @MockitoBean
    private BulkEvaluationService bulkEvaluationService;

    @MockitoBean
    private com.instacommerce.featureflag.exception.TraceIdProvider traceIdProvider;

    @MockitoBean
    private org.springframework.cache.CacheManager cacheManager;

    @Test
    @DisplayName("GET /flags returns map of all evaluated flags")
    void evaluateAllFlags() throws Exception {
        when(bulkEvaluationService.evaluateAllActiveFlags(eq("usr_123"), any()))
            .thenReturn(Map.of("mobile_home_v2", true, "express_delivery", false));

        mockMvc.perform(get("/flags")
                .param("userId", "usr_123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mobile_home_v2").value(true))
            .andExpect(jsonPath("$.express_delivery").value(false));
    }

    @Test
    @DisplayName("GET /flags/{key} evaluates single flag with string userId")
    void evaluateSingleFlagWithStringUserId() throws Exception {
        when(flagEvaluationService.evaluate(eq("dark_mode"), any(), any()))
            .thenReturn(new FlagEvaluationResponse("dark_mode", true, FlagEvaluationResponse.SOURCE_DEFAULT));

        mockMvc.perform(get("/flags/dark_mode")
                .param("userId", "usr_abc_456"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("dark_mode"))
            .andExpect(jsonPath("$.value").value(true))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("POST /flags/bulk returns results and evaluations")
    void evaluateBulkFlags() throws Exception {
        Map<String, FlagEvaluationResponse> evaluations = Map.of(
            "flag1", new FlagEvaluationResponse("flag1", true, "DEFAULT")
        );
        BulkEvaluationResponse response = new BulkEvaluationResponse(evaluations);

        when(bulkEvaluationService.evaluateAll(any(), any(), any()))
            .thenReturn(response);

        BulkEvaluationRequest request = new BulkEvaluationRequest(List.of("flag1"), UUID.randomUUID(), null);

        mockMvc.perform(post("/flags/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evaluations.flag1.key").value("flag1"))
            .andExpect(jsonPath("$.results.flag1.key").value("flag1"));
    }
}
