package com.instacommerce.warehouse.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.warehouse.config.JacksonConfig;
import com.instacommerce.warehouse.config.SecurityConfig;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.GlobalExceptionHandler;
import com.instacommerce.warehouse.exception.TraceIdProvider;
import com.instacommerce.warehouse.security.InternalServiceAuthFilter;
import com.instacommerce.warehouse.security.JwtAuthenticationFilter;
import com.instacommerce.warehouse.security.JwtService;
import com.instacommerce.warehouse.security.RestAccessDeniedHandler;
import com.instacommerce.warehouse.security.RestAuthenticationEntryPoint;
import com.instacommerce.warehouse.service.CapacityService;
import com.instacommerce.warehouse.service.StoreService;
import com.instacommerce.warehouse.service.ZoneService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminStoreController.class, StoreController.class})
@Import({
    SecurityConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class,
    GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-service-token"
})
class AdminStoreControllerZoneSecurityTest {

    private static final String INTERNAL_TOKEN = "test-internal-service-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private CapacityService capacityService;

    @MockitoBean
    private ZoneService zoneService;

    @SuppressWarnings("unchecked")
    private void stubJwt(String token, String subject, String role) {
        Jws<Claims> jws = Mockito.mock(Jws.class);
        Claims claims = Mockito.mock(Claims.class);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn(subject);
        Collection<? extends GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_" + role));
        when(jwtService.validateAccessToken(token)).thenReturn(jws);
        when(jwtService.extractAuthorities(claims)).thenReturn((Collection) authorities);
    }

    @Test
    @DisplayName("admin zone CRUD endpoints allow ADMIN JWT and INTERNAL_SERVICE token")
    void adminZoneCrudAllowsAdminAndInternalService() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        StoreResponse.ZoneResponse zone = new StoreResponse.ZoneResponse(
            zoneId, "Koramangala", "560034", new BigDecimal("5.00")
        );
        stubJwt("admin-jwt", "admin-user", "ADMIN");
        when(zoneService.createZone(eq(storeId), any())).thenReturn(zone);
        when(zoneService.getStoreZones(storeId)).thenReturn(List.of(zone));
        when(zoneService.getZone(storeId, zoneId)).thenReturn(zone);
        when(zoneService.updateZone(eq(storeId), eq(zoneId), any())).thenReturn(
            new StoreResponse.ZoneResponse(zoneId, "Koramangala West", "560035", new BigDecimal("6.50"))
        );
        doNothing().when(zoneService).deleteZone(storeId, zoneId);

        mockMvc.perform(post("/admin/stores/{id}/zones", storeId)
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"zoneName":"Koramangala","pincode":"560034","deliveryRadiusKm":5.0}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pincode").value("560034"));

        mockMvc.perform(get("/admin/stores/{id}/zones", storeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].zoneName").value("Koramangala"));

        mockMvc.perform(get("/admin/stores/{id}/zones/{zoneId}", storeId, zoneId)
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(zoneId.toString()));

        mockMvc.perform(put("/admin/stores/{id}/zones/{zoneId}", storeId, zoneId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"zoneName":"Koramangala West","pincode":"560035","deliveryRadiusKm":6.5}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pincode").value("560035"));

        mockMvc.perform(delete("/admin/stores/{id}/zones/{zoneId}", storeId, zoneId)
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN))
            .andExpect(status().isNoContent());

        verify(zoneService).deleteZone(storeId, zoneId);
    }

    @Test
    @DisplayName("admin zone endpoints return 403 for plain USER and 401 for anonymous or invalid internal token")
    void adminZoneEndpointsRejectUserAndAnonymous() throws Exception {
        UUID storeId = UUID.randomUUID();
        stubJwt("user-jwt", "regular-user", "USER");

        mockMvc.perform(post("/admin/stores/{id}/zones", storeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"zoneName":"Koramangala","pincode":"560034","deliveryRadiusKm":5.0}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/stores/{id}/zones", storeId))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/stores/{id}/zones", storeId)
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"zoneName":"Koramangala","pincode":"560034","deliveryRadiusKm":5.0}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("public store zone read endpoints remain accessible without auth")
    void publicZoneEndpointsPermitAll() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(zoneService.getStoreZones(storeId)).thenReturn(List.of());
        when(zoneService.mapPincodeToStoreIds("560034")).thenReturn(List.of(storeId));

        mockMvc.perform(get("/stores/{id}/zones", storeId))
            .andExpect(status().isOk());

        mockMvc.perform(get("/stores/by-pincode").param("pincode", "560034"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value(storeId.toString()));
    }
}
