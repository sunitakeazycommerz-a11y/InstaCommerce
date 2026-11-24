package com.instacommerce.riderfleet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.platform.security.InternalServiceSecurityConfiguration;
import com.instacommerce.riderfleet.config.JacksonConfig;
import com.instacommerce.riderfleet.config.SecurityConfig;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.dto.response.ShiftResponse;
import com.instacommerce.riderfleet.exception.GlobalExceptionHandler;
import com.instacommerce.riderfleet.exception.TraceIdProvider;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.security.JwtAuthenticationFilter;
import com.instacommerce.riderfleet.security.JwtService;
import com.instacommerce.riderfleet.security.RestAccessDeniedHandler;
import com.instacommerce.riderfleet.security.RestAuthenticationEntryPoint;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import com.instacommerce.riderfleet.service.RiderAvailabilityService;
import com.instacommerce.riderfleet.service.RiderEarningsService;
import com.instacommerce.riderfleet.service.RiderService;
import com.instacommerce.riderfleet.service.RiderShiftService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.time.Instant;
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

@WebMvcTest(controllers = {RiderController.class, RiderAssignmentController.class})
@Import({
    SecurityConfig.class,
    InternalServiceSecurityConfiguration.class,
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
class RiderControllerSecurityAndShiftTest {

    private static final String INTERNAL_TOKEN = "test-internal-service-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RiderService riderService;
    @MockitoBean private RiderAvailabilityService availabilityService;
    @MockitoBean private RiderEarningsService earningsService;
    @MockitoBean private RiderAvailabilityRepository availabilityRepository;
    @MockitoBean private RiderShiftService shiftService;
    @MockitoBean private RiderAssignmentService assignmentService;

    private void stubJwt(String token, String subject, String role) {
        Jws<Claims> jws = Mockito.mock();
        Claims claims = Mockito.mock(Claims.class);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn(subject);
        Collection<? extends GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_" + role));
        when(jwtService.validateAccessToken(token)).thenReturn(jws);
        doReturn(authorities).when(jwtService).extractAuthorities(claims);
    }

    @Test
    @DisplayName("GET /api/riders/available denies CUSTOMER/RIDER tokens and permits ADMIN or internal token")
    void availableRidersAuthEnforcedOnBothPaths() throws Exception {
        UUID storeId = UUID.randomUUID();
        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(UUID.randomUUID());
        availability.setStoreId(storeId);
        availability.setAvailable(true);
        when(availabilityRepository.findByIsAvailableTrueAndStoreId(storeId))
            .thenReturn(List.of(availability));

        stubJwt("customer-token", UUID.randomUUID().toString(), "CUSTOMER");
        stubJwt("rider-token", UUID.randomUUID().toString(), "RIDER");
        stubJwt("admin-token", UUID.randomUUID().toString(), "ADMIN");

        mockMvc.perform(get("/api/riders/available").param("storeId", storeId.toString()))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/riders/available")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                .param("storeId", storeId.toString()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/riders/available")
                .header(HttpHeaders.AUTHORIZATION, "Bearer rider-token")
                .param("storeId", storeId.toString()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/riders/available")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .param("storeId", storeId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].storeId").value(storeId.toString()));

        mockMvc.perform(get("/api/riders/available")
                .header("X-Internal-Service", "fulfillment-service")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .param("storeId", storeId.toString()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/riders/assign blocks CUSTOMER role and allows internal/ADMIN callers")
    void assignEndpointEnforcesRole() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        when(assignmentService.assignRider(eq(orderId), eq(storeId), any(), any(), any(), any()))
            .thenReturn(riderId);

        stubJwt("customer-token", UUID.randomUUID().toString(), "CUSTOMER");
        stubJwt("admin-token", UUID.randomUUID().toString(), "ADMIN");

        String body = """
            {
              "orderId": "%s",
              "storeId": "%s",
              "pickupLat": 12.9716,
              "pickupLng": 77.5946
            }
            """.formatted(orderId, storeId);

        mockMvc.perform(post("/api/riders/assign")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/riders/assign")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.riderId").value(riderId.toString()));
    }

    @Test
    @DisplayName("Shift CRUD endpoints allow owning rider and ADMIN while blocking other riders")
    void shiftCrudAndBolaOwnership() throws Exception {
        UUID riderId = UUID.randomUUID();
        UUID otherRiderId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        Instant start = Instant.parse("2026-03-20T09:00:00Z");
        Instant end = Instant.parse("2026-03-20T17:00:00Z");

        ShiftResponse scheduled = new ShiftResponse(shiftId, riderId, start, end, "SCHEDULED");
        ShiftResponse active = new ShiftResponse(shiftId, riderId, start, end, "ACTIVE");

        when(shiftService.createShift(eq(riderId), any())).thenReturn(scheduled);
        when(shiftService.listShifts(riderId, "SCHEDULED")).thenReturn(List.of(scheduled));
        when(shiftService.updateShift(eq(riderId), eq(shiftId), any())).thenReturn(active);

        stubJwt("owner-token", riderId.toString(), "RIDER");
        stubJwt("other-token", otherRiderId.toString(), "RIDER");
        stubJwt("admin-token", UUID.randomUUID().toString(), "ADMIN");

        String createBody = """
            {
              "shiftStart": "2026-03-20T09:00:00Z",
              "shiftEnd": "2026-03-20T17:00:00Z",
              "status": "SCHEDULED"
            }
            """;

        mockMvc.perform(post("/api/riders/{id}/shifts", riderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer owner-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(shiftId.toString()))
            .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mockMvc.perform(get("/riders/{id}/shifts", riderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer other-token")
                .param("status", "SCHEDULED"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/riders/{id}/shifts", riderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer owner-token")
                .param("status", "SCHEDULED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("SCHEDULED"));

        mockMvc.perform(patch("/api/riders/{id}/shifts/{shiftId}", riderId, shiftId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
