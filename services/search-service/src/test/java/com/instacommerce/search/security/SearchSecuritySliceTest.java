package com.instacommerce.search.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.search.config.CacheConfig;
import com.instacommerce.search.config.JacksonConfig;
import com.instacommerce.search.config.SecurityConfig;
import com.instacommerce.search.controller.SearchController;
import com.instacommerce.search.exception.GlobalExceptionHandler;
import com.instacommerce.search.exception.TraceIdProvider;
import com.instacommerce.search.service.SearchService;
import com.instacommerce.search.service.TrendingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(classes = SearchSecuritySliceTest.SliceConfig.class)
@TestPropertySource(properties = "search.cache.redis.enabled=true")
@DisplayName("Search Security WebMvc Slice Tests")
class SearchSecuritySliceTest {

    @Configuration
    @EnableWebMvc
    @Import({
        SecurityConfig.class,
        CacheConfig.class,
        JacksonConfig.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        TraceIdProvider.class,
        SearchController.class
    })
    static class SliceConfig {
        @Bean
        SearchService searchService() {
            return mock(SearchService.class);
        }

        @Bean
        TrendingService trendingService() {
            return mock(TrendingService.class);
        }

        @Bean
        JwtService jwtService() {
            return mock(JwtService.class);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TrendingService trendingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    @DisplayName("GET /search/trending is publicly permitted even without Redis")
    void publicGetSearchEndpointPermittedWithoutRedis() throws Exception {
        when(trendingService.getTrending(10)).thenReturn(List.of("milk"));

        mockMvc.perform(get("/search/trending"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Non-GET requests under /search/** require authentication")
    void nonGetRequestsUnderSearchRequireAuth() throws Exception {
        mockMvc.perform(post("/search/trending"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/admin/** requires ROLE_ADMIN")
    void adminEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get("/admin/index"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/index").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isForbidden());
    }
}
