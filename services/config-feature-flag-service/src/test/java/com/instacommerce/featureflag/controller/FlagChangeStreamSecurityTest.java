package com.instacommerce.featureflag.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.featureflag.config.JacksonConfig;
import com.instacommerce.featureflag.exception.TraceIdProvider;
import com.instacommerce.featureflag.security.InternalServiceAuthFilter;
import com.instacommerce.featureflag.security.JwtAuthenticationFilter;
import com.instacommerce.featureflag.security.JwtService;
import com.instacommerce.featureflag.security.RestAccessDeniedHandler;
import com.instacommerce.featureflag.security.RestAuthenticationEntryPoint;
import com.instacommerce.featureflag.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = FlagChangeStreamController.class)
@Import({
    SecurityConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-service-token",
    "feature-flag.cache.redis-enabled=false"
})
class FlagChangeStreamSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CacheManager cacheManager;

    @Test
    @DisplayName("GET /flags/stream requires authentication and permits authenticated callers")
    void streamRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/flags/stream"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/flags/stream")
                        .with(user("frontend-client").roles("USER")))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }
}
